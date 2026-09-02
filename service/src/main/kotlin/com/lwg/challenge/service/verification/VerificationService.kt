package com.lwg.challenge.service.verification

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.common.exception.OneButtonDialogException
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.notification.NotificationType
import com.lwg.challenge.domain.photo.PhotoStorage
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationRepository
import com.lwg.challenge.domain.verification.VerificationStatus
import com.lwg.challenge.service.notification.ChallengeNotificationEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDateTime

/**
 * 챌린지 인증 — 사진 제출 / 조회 (challenge-verification T-B2·T-B3·T-B4).
 *
 * ## 🔴 이 서비스는 `verifications` row 를 만들지 않는다
 *
 * PENDING row 2건은 **`ChallengeCommandService.accept()` 가 이미 만든다** (`pendingVerification()`).
 * `soul-oath` 에서 들어갔고 `uq_verifications_challenge_user` 가 중복을 막는다.
 * **여기서는 있는 row 를 채운다.** 백로그가 한때 이 항목을 후속 feature 로 남겨 뒀으나
 * 2026-08-14 정정됐다 (spec §0.2).
 *
 * ## 🔴 `photo_url` 에는 **URL 이 아니라 key** 가 들어간다
 *
 * 컬럼 이름은 V1 부터 `photo_url` 이지만 저장하는 값은 [PhotoStorage] 의 **key** 다.
 * URL 을 저장하면 **호스트가 DB 에 박제된다** — 오브젝트 스토리지로 이사하면 옛 row 의 URL 이
 * 전부 죽는다. key 는 저장소가 바뀌어도 같은 사진을 가리키므로 이사 비용이 0 이다.
 * `V9__verification_photo_key_recomment.sql` 이 컬럼 주석을 정정한다 (DML 0건 — V8 선례).
 *
 * ## 🔴 접근 제어는 여기서 한다 — 요청 시점 JWT 당사자 검사 (2026-08-18 2차 결정)
 *
 * ADR-0011 §4 의 원칙(*"접근 제어는 서버가 한다"*)은 유지하되 수단이 바뀌었다. 서명 URL 을
 * 폐기하고 **사진 요청이 들어올 때마다 [readPhoto] 가 JWT principal 로 당사자를 판정**한다.
 * `ChallengeDetailService` 의 당사자 검사와 같은 형태다.
 *
 * ## 에러 코드
 *
 * `ChallengeCommandService` 의 배분을 그대로 따른다:
 * - **700 (스낵바)** — 사용자가 행동을 고치면 되는 것 + 권한 위반
 * - **705 (단일버튼 다이얼로그)** — *"화면이 이미 낡았다"* 는 신호 (없음 / 상태가 다름)
 *
 * 모바일은 `message` 를 그대로 노출하므로 **여기 문자열은 전부 UI 텍스트다.**
 *
 * ⚠️ **[readPhoto] 만 예외다** — 바이트를 돌려주는 경로라 `BusinessException` 을 쓰지 않는다.
 * 이유는 [readPhoto] KDoc 참조.
 *
 * ## 🔴 제출은 `last-write-wins` 다 — 그래서 [submit] 에 `@Transactional` 이 없다
 *
 * 마감 전까지 사진을 몇 번이든 교체할 수 있다 (verification-photo-replace, 2026-09-02).
 * 교체는 **이전 파일을 참조 0 으로 만들고**, 그 파일은 **커밋이 끝난 뒤에** 지워야 한다
 * ([PhotoStorage.delete] 는 트랜잭션이 아니다). 그래서 트랜잭션 경계를
 * [VerificationSubmitTransactionalWorker] 로 빼고 [submit] 은 경계 **밖**에 선다 —
 * `WithdrawalService` 와 같은 구조이며 같은 이유다.
 */
@Service
class VerificationService(
    private val challengeRepository: ChallengeRepository,
    private val verificationRepository: VerificationRepository,
    /**
     * [getVerifications] 가 양측의 `status` 를 봐야 하기 때문에 있다 — `photoDeleted` 판정에만
     * 쓰인다. 제출·사진 읽기 경로는 이 저장소를 건드리지 않는다.
     */
    private val userRepository: UserRepository,
    private val photoStorage: PhotoStorage,
    /** 제출의 **트랜잭션 경계**. 별도 빈인 이유는 그 클래스 KDoc 참조 (self-invocation 함정). */
    private val submitWorker: VerificationSubmitTransactionalWorker,
) {

    private val log = LoggerFactory.getLogger(VerificationService::class.java)

    // ─── 1. 제출 (T-B2 + T-B4) ───────────────────────────────────

    /**
     * multipart 로 받은 사진을 저장하고 내 인증을 확정한다 — `VERIFIED` + `verified_at` + 상대에게 알림.
     *
     * ## 재제출 정책 — **`last-write-wins`** (계약 §3, 2026-09-02 개정)
     *
     * | 상황 | 서버 |
     * |---|---|
     * | `PENDING` 에서 제출 | ✅ `VERIFIED` 전이 + 사진 저장 + 상대에게 알림 |
     * | 이미 `VERIFIED` (재제출) | ✅ **사진 교체** + `verified_at` 갱신 + **이전 파일 삭제**. 알림은 **보내지 않는다** |
     *
     * 🔴 **마지막에 올린 사진이 최종본이다.** 마감(`IN_PROGRESS`) 전까지 몇 번이든 바꿀 수 있고,
     * 상대와 판정 배치가 보는 것도 마지막 사진이다.
     *
     * ### 왜 전면 거부에서 뒤집었나 — **정책 뒤집기가 아니라 전제 소멸이다**
     *
     * 거부를 택했던 근거는 *"판별 수단이 없다"* 가 아니라 **"올린 사진이 조용히 버려진다"** 였다.
     * multipart 에는 *"같은 촬영본의 재시도"* 와 *"다른 사진으로 교체"* 를 가를 수단이 없어서,
     * 멱등 성공으로 두면 **사용자가 다시 찍어 올린 사진이 버려지는데 앱은 성공이라고 답한다** —
     * 상대가 보는 것과 사용자가 올렸다고 믿는 것이 갈라진다. 그게 막으려던 실패였다.
     *
     * 🔑 **`last-write-wins` 는 올린 사진을 저장한다.** 버려지는 사진이 0 이므로 그 실패 모드가
     * **발생할 수 없다.** 두 해석이 갈라지지 않으니 거부해야 할 이유도 함께 사라진다.
     * 증거 보존(기획서 §2.4)은 *"마감 시점의 사진이 최종본이고 그 뒤로 불변"* 으로 여전히 성립한다 —
     * 마감 후에는 `IN_PROGRESS` 검사가 교체를 막는다.
     *
     * 🔵 **회복 절차가 필요 없어졌다.** 응답을 유실했으면 **그냥 다시 올리면 된다.** 앱이 재시도
     * 전에 `GET /challenges/{id}/verifications` 로 status 를 확인하던 절차는 소멸했다 (계약 §3).
     *
     * ## 검사 순서 — **권한·상태 → 페이로드 → 저장 → row → 알림**
     *
     * 권한을 가장 먼저 보는 이유는 [VerificationSubmitTransactionalWorker.loadInProgressAsParticipant]
     * 참조 — 제3자에게 상태를 흘리지 않는다. ⚠️ **교체 경로도 이 검사를 그대로 지난다**:
     * 당사자가 아니거나 `IN_PROGRESS` 가 아니거나 JPEG 이 아니면 여전히 거부되고, 거부된 요청은
     * 디스크에 아무것도 남기지 않으며 **이전 사진도 지우지 않는다.**
     *
     * ## 🔴 이 메서드에 `@Transactional` 이 없고, 그게 의도다
     *
     * 붙이면 [VerificationSubmitTransactionalWorker.submit] 의 커밋이 이 메서드가 끝날 때까지
     * 미뤄져 **이전 파일 삭제가 커밋 전에** 실행된다. 그러면 롤백됐을 때 row 는 옛 key 를
     * 가리키는데 **파일은 이미 사라진** 상태가 남는다 — [PhotoStorage.delete] 는 트랜잭션이 아니라
     * 되돌아오지 않는다. `WithdrawalService.withdraw` 가 같은 이유로 같은 모양이다.
     *
     * ## 고아 파일
     *
     * 파일 저장과 row 기록이 **같은 트랜잭션**이라 *"올라갔는데 제출이 안 된"* 고아는 없다.
     * 남는 두 경로는 **①파일 저장 후 커밋 실패**(새 파일이 고아) 와 **②커밋 후 이전 파일 삭제
     * 실패**(옛 파일이 고아)뿐이며, 둘 다 **읽히지 않으므로 무해하다.** ②의 실패를 예외로
     * 올리지 않는 이유는 [deleteReplacedPhotoQuietly] 참조.
     */
    fun submit(me: Long, challengeId: Long, photo: ByteArray): Verification {
        val outcome = submitWorker.submit(me, challengeId, photo)
        deleteReplacedPhotoQuietly(me, challengeId, outcome.replacedPhotoKey)
        return outcome.verification
    }

    /**
     * 교체로 참조가 끊긴 이전 사진 파일을 지운다. **커밋이 끝난 뒤에만 불린다.**
     *
     * 🔴 **실패는 삼키고 WARN 만 남긴다.** 여기서 예외가 새면 **이미 커밋된 교체가 실패로
     * 응답된다** — 사용자는 사진이 안 바뀐 줄 알고 다시 올리고, 그때마다 또 고아가 하나씩
     * 늘어난다. 삭제가 실패해서 남는 것은 아무 row 도 가리키지 않는 파일 하나뿐이고 기능은
     * 정상이다 (spec §6). `WithdrawalService.deletePhotosQuietly` 와 같은 판단이다.
     */
    private fun deleteReplacedPhotoQuietly(me: Long, challengeId: Long, key: String?) {
        if (key == null) return
        runCatching { photoStorage.delete(key) }
            .onFailure { e ->
                log.warn(
                    "교체된 인증 사진 삭제 실패 (고아 파일로 남는다): userId={}, challengeId={}, key={}, cause={}",
                    me,
                    challengeId,
                    key,
                    e.message,
                )
            }
    }

    // ─── 2. 조회 (T-B3) ──────────────────────────────────────────

    /**
     * 양측 인증 상태 + **만료 없는 사진 경로** (계약 §4).
     *
     * 🔴 **경로는 당사자 검사를 통과한 사람에게만 내려간다** (ADR-0011 §4). 다만 진짜 방어는
     * 경로를 숨기는 것이 아니라 [readPhoto] 의 요청 시점 검사다 — 경로는 추측 가능한 고정
     * 형태이고, 제3자가 알아내도 사진을 열 수 없다.
     *
     * `challenger`/`opponent` 를 **역할 그대로** 준다 — `ChallengeDetailService` 와 같은 시점이라
     * 상세 화면에서 두 응답을 합칠 때 뒤집기 계산이 없다.
     *
     * `IN_PROGRESS` 를 요구하지 않는다. 챌린지가 끝난 뒤에도 *"그때 뭘 올렸는지"* 는 보여야 한다.
     * row 가 없으면(수락 전) 양쪽 다 `PENDING` 으로 나간다 — `Verification` KDoc 의
     * *"row 자체가 없으면 PENDING 으로 간주"* 와 같은 규칙이다.
     *
     * ## 🔴 쿼리가 2개에서 3개로 늘었다 — `photoDeleted` 때문이다 (mypage T-B1 부록)
     *
     * 양측 `users` 를 한 번에 조회한다. [PartyVerificationView.photoDeleted] 를 판정하려면
     * 그 party 가 탈퇴했는지를 알아야 하는데, 그 정보는 `verifications` 에 없다.
     * **새로운 패턴은 아니다** — `ChallengeDetailService.getDetail` 이 이미 같은 자리에서
     * `findAllByIds(setOf(challengerId, opponentId))` 를 하고 있다. 두 응답이 상세 화면에서
     * 합쳐지므로 조회 모양이 같은 편이 낫다.
     */
    @Transactional(readOnly = true)
    fun getVerifications(me: Long, challengeId: Long): VerificationsView {
        val challenge = challengeRepository.loadAsParticipant(challengeId, me)
        val byUser = verificationRepository.findAllByChallengeIds(listOf(challengeId))
            .associateBy { it.userId }
        val users = userRepository.findAllByIds(setOf(challenge.challengerId, challenge.opponentId))

        return VerificationsView(
            challengeId = challengeId,
            challenger = view(
                challengeId = challengeId,
                party = VerificationParty.CHALLENGER,
                userId = challenge.challengerId,
                verification = byUser[challenge.challengerId],
                ownerStatus = users[challenge.challengerId]?.status,
            ),
            opponent = view(
                challengeId = challengeId,
                party = VerificationParty.OPPONENT,
                userId = challenge.opponentId,
                verification = byUser[challenge.opponentId],
                ownerStatus = users[challenge.opponentId]?.status,
            ),
        )
    }

    // ─── 3. 사진 읽기 (2026-08-18 2차 결정 — 서명 URL 폐기) ──────

    /**
     * [party] 쪽 인증 사진의 바이트. 당사자 검사를 **요청 시점에 JWT 로** 한다.
     *
     * ## 🔴 예외를 던지지 않는다 — sealed result 로 답한다
     *
     * 이 경로의 응답은 `BaseResponse` 가 아니라 **바이트**다. 여기서 `BusinessException` 을
     * 던지면 `GlobalExceptionHandler` 가 **HTTP 200 + JSON** 으로 바꾸는데, 앱 이미지 로더는
     * 그 JSON 을 이미지로 파싱하려다 깨지고, status 도 200 이라 실패를 감지할 수도 없다.
     * 그래서 [PhotoReadResult] 를 돌려주고 컨트롤러가 HTTP status 로 매핑한다 — 폐기된
     * `ServeOutcome` 이 쓰던 것과 같은 방식이다.
     *
     * ## 판정 순서
     *
     * 챌린지 없음 → [PhotoReadResult.NotFound], **당사자 아님 → [PhotoReadResult.Forbidden]**,
     * 해당 party 가 `VERIFIED` 가 아니거나 key·파일이 없음 → [PhotoReadResult.NotFound].
     * 권한을 상태보다 먼저 본다 — 제3자에게 인증 여부를 흘리지 않는다 ([loadInProgressAsParticipant]
     * 와 같은 이유).
     *
     * ⚠️ **탈퇴한 사용자의 사진은 코드 변경 없이 이미 404 다** (mypage T-B2). 탈퇴는 row 를
     * 남긴 채 `photo_url` 만 NULL 로 미는데, 아래 `verification.photoUrl ?: return NotFound`
     * 가 그걸 그대로 받아낸다.
     *
     * `IN_PROGRESS` 를 요구하지 않는다 — 조회([getVerifications])와 같은 이유로, 끝난 챌린지의
     * 사진도 보여야 한다.
     *
     * ## 🔴 [PhotoReadResult.Found.etag] — 같은 경로가 **다른 바이트**를 낼 수 있게 됐다
     *
     * 사진 교체가 열리면서(verification-photo-replace) `/challenges/{id}/photos/{party}` 는
     * **불변 자원이 아니게 됐다.** 경로는 그대로인데 내용이 바뀌므로 캐시가 옛 사진을 계속
     * 보여줄 수 있고, 그 실패는 **에러도 로그도 없이 조용하다.** 그래서 [photoETag] 가 key 로
     * 버전 표를 만들어 컨트롤러가 조건부 GET(`If-None-Match` → 304)을 할 수 있게 한다.
     */
    @Transactional(readOnly = true)
    fun readPhoto(me: Long, challengeId: Long, party: VerificationParty): PhotoReadResult {
        val challenge = challengeRepository.findById(challengeId)
            ?: return PhotoReadResult.NotFound
        if (me != challenge.challengerId && me != challenge.opponentId) {
            return PhotoReadResult.Forbidden
        }

        val targetUserId = when (party) {
            VerificationParty.CHALLENGER -> challenge.challengerId
            VerificationParty.OPPONENT -> challenge.opponentId
        }
        val verification = verificationRepository.findAllByChallengeIds(listOf(challengeId))
            .firstOrNull { it.userId == targetUserId }
            ?: return PhotoReadResult.NotFound
        if (verification.status != VerificationStatus.VERIFIED) return PhotoReadResult.NotFound
        val key = verification.photoUrl ?: return PhotoReadResult.NotFound

        val bytes = photoStorage.read(key) ?: return PhotoReadResult.NotFound
        return PhotoReadResult.Found(bytes = bytes, contentType = "image/jpeg", etag = photoETag(key))
    }

    // ─── 내부 helper ─────────────────────────────────────────────

    /**
     * 한 party 의 응답 조각. **사진 자리는 세 갈래**이며 그 구분이 이 함수의 전부다.
     *
     * | 조합 | `photoUrl` | `photoDeleted` | 앱이 그리는 것 |
     * |---|---|---|---|
     * | `VERIFIED` + key 있음 | 경로 | `false` | 사진 |
     * | `VERIFIED` + key 없음 + owner `DELETED` | `null` | **`true`** | *"탈퇴로 삭제된 사진"* |
     * | 그 외 (`PENDING`/`FAILED`, 또는 `VERIFIED` + key 없음인데 owner 가 살아 있음) | `null` | `false` | 사진 없음 / 로드 실패 |
     *
     * 🔴 **세 번째 줄의 마지막 경우가 이 필드를 만든 이유다.** 그건 *"인증은 됐는데 URL 이
     * 없는 비정상"* 이고 앱이 `"사진을 불러오지 못했어요"` 로 그린다. 탈퇴자의 삭제된 사진이
     * 그 문구를 빌려 쓰면 (1) **영구 상태를 일시적 실패의 어휘로** 말하게 되고, (2) 더 나쁘게는
     * **진짜 데이터 이상이 탈퇴자 뒤에 숨는다.** owner 의 `status` 까지 봐야 둘이 갈린다.
     *
     * ⚠️ owner row 자체가 없으면([ownerStatus] 가 null) `photoDeleted` 는 `false` 다 —
     * 탈퇴는 row 를 지우지 않으므로(mypage T-B2) row 부재는 탈퇴가 아니라 데이터 이상이고,
     * 그건 위 표의 세 번째 줄에 속하는 것이 맞다.
     *
     * @param ownerStatus 이 party 사용자의 계정 상태. row 가 없으면 null.
     */
    private fun view(
        challengeId: Long,
        party: VerificationParty,
        userId: Long,
        verification: Verification?,
        ownerStatus: UserStatus?,
    ): PartyVerificationView {
        val status = verification?.status ?: VerificationStatus.PENDING
        val key = verification?.photoUrl
        // 판단 기준은 status 가 아니라 **key 유무**다 — 탈퇴는 row(status/verified_at)를 보존한
        // 채 사진 파일과 key 만 지우기 때문이다 (mypage T-B2). 원래부터 key 로 보고 있던 덕분에
        // 이 줄은 그 feature 가 들어올 때도 고칠 것이 없었다.
        val hasPhoto = status == VerificationStatus.VERIFIED && key != null
        return PartyVerificationView(
            userId = userId,
            status = status,
            photoUrl = if (hasPhoto) photoPath(challengeId, party) else null,
            verifiedAt = verification?.verifiedAt,
            photoDeleted = status == VerificationStatus.VERIFIED &&
                key == null &&
                ownerStatus == UserStatus.DELETED,
        )
    }

    /** [readPhoto] 를 서빙하는 컨트롤러 경로. **상대 경로**다 — 앱이 자기 baseUrl 에 붙인다. */
    private fun photoPath(challengeId: Long, party: VerificationParty): String =
        "/api/v1/challenges/$challengeId/photos/${party.name.lowercase()}"

    companion object {
        // ── 사용자 노출 문구 (api-contract 확정. 변경 시 계약 + 테스트 동시 수정) ──
        const val MSG_NOT_FOUND = "챌린지를 찾을 수 없어요"
        const val MSG_NOT_MINE = "내 챌린지가 아니에요"
        const val MSG_NOT_IN_PROGRESS = "진행 중인 챌린지만 인증할 수 있어요"
        const val MSG_PHOTO_REQUIRED = "사진을 첨부해주세요"
        const val MSG_NOT_JPEG = "JPEG 사진만 올릴 수 있어요"
        const val MSG_VERIFICATION_MISSING = "인증 정보를 찾을 수 없어요"
    }
}

/**
 * [VerificationService.submit] 의 **트랜잭션 경계**만 담당하는 협력 빈
 * (verification-photo-replace T-B1).
 *
 * 🔴 **별도 빈인 이유는 하나뿐이다 — self-invocation 이 프록시를 타지 않기 때문이다.**
 * 이 메서드가 [VerificationService] 안에 있었다면 `this.submit(...)` 호출에서 `@Transactional`
 * 이 **조용히 무시되고** 파일 저장과 row 저장이 각각 따로 커밋된다. 반대로 바깥 [VerificationService.submit]
 * 에 `@Transactional` 을 붙이면 **이전 파일 삭제가 커밋 전으로 당겨진다.** 둘 다 컴파일되고
 * 테스트도 통과하는데 운영에서만 깨지는 종류라 구조로 막는다 — `WithdrawalTransactionalWorker`
 * 와 같은 이유·같은 모양이다.
 *
 * 그러니 이 클래스를 "불필요한 래퍼" 로 보고 [VerificationService] 안으로 합치지 마라.
 */
@Service
class VerificationSubmitTransactionalWorker(
    private val challengeRepository: ChallengeRepository,
    private val verificationRepository: VerificationRepository,
    private val photoStorage: PhotoStorage,
    private val clock: Clock,
    /** 알림은 커밋 후에 나간다 — `NotificationDispatcher` 가 `AFTER_COMMIT` 으로 받는다. */
    private val eventPublisher: ApplicationEventPublisher,
) {

    /**
     * 사진을 저장하고 내 인증을 확정한다. 정책·검사 순서는 [VerificationService.submit] KDoc.
     *
     * 🔴 **여기서 이전 파일을 지우지 않는다.** key 를 [SubmitOutcome.replacedPhotoKey] 로
     * 돌려주고 **호출자가 커밋 후에** 지운다. 여기서 지우면 롤백돼도 파일이 돌아오지 않아
     * *"row 는 옛 key 를 가리키는데 파일이 없는"* 상태가 남는다 ([PhotoStorage.delete] KDoc).
     *
     * ⚠️ **이전 key 를 지워도 되는 근거는 [PhotoStorage.store] 의 *"부를 때마다 새 key"* 규약이다.**
     * 내용 주소 방식처럼 같은 바이트에 같은 key 를 돌려주는 구현으로 바꾸면 이 삭제가
     * **방금 저장한 파일을 지운다.** 규약을 깨려면 여기부터 고쳐라.
     */
    @Transactional
    fun submit(me: Long, challengeId: Long, photo: ByteArray): SubmitOutcome {
        val challenge = loadInProgressAsParticipant(challengeId, me)
        val mine = myVerification(challengeId, me)

        if (photo.isEmpty()) {
            throw SnackbarException(VerificationService.MSG_PHOTO_REQUIRED)
        }
        // 저장된 바이트는 나중에 `Content-Type: image/jpeg` 로 그대로 서빙된다. 선언한 타입과
        // 실제 바이트가 다른 파일이 저장소에 들어가는 경로를 여기서 끊는다. 사용자 노출 문구를
        // 만들어야 하는 검사라 저장소가 아니라 서비스가 주인이다.
        if (!isJpeg(photo)) {
            throw SnackbarException(VerificationService.MSG_NOT_JPEG)
        }

        // 🔴 "처음 인증인가" 는 사진을 저장하기 **전에** 봐야 한다. 저장 뒤에는 row 가 이미
        //    VERIFIED 로 덮여 구분할 수 없다. 알림 1회 규칙이 여기에 매달려 있다.
        val firstVerification = mine.status != VerificationStatus.VERIFIED
        // 참조가 끊기는 파일은 status 가 아니라 **직전 key** 로 판정한다. 탈퇴가 row 를 남긴 채
        // key 만 NULL 로 밀기 때문에(mypage T-B2) status 로 보면 없는 파일을 지우러 간다.
        val replacedPhotoKey = mine.photoUrl

        val key = photoStorage.store(challenge.requiredId(), me, photo)

        val saved = verificationRepository.save(
            mine.copy(
                status = VerificationStatus.VERIFIED,
                photoUrl = key,
                // 🔴 교체할 때마다 갱신된다 — `verified_at` 은 **최종 제출 시각**이다.
                verifiedAt = LocalDateTime.now(clock),
            ),
        )

        // 🔴 알림은 **최초 1회만**이다. 교체할 때마다 보내면 사진을 다듬는 동안 상대의 알림함이
        //    같은 문구로 채워지고, 그 알림들은 누를 이유도 없다 (spec §2·§3).
        if (firstVerification) {
            // 🔴 발송은 커밋 후다 (push-fcm 결정사항 3번). 알림 실패가 인증을 롤백시키면 안 된다.
            //    롤백되면 커밋이 없으므로 리스너가 아예 실행되지 않는다 — 미발송이 공짜로 성립한다.
            eventPublisher.publishEvent(
                ChallengeNotificationEvent(
                    // 인증했다는 사실을 알릴 대상은 **상대**다. 인증한 본인이 아니다.
                    recipientId = challenge.opponentOf(me),
                    actorId = me,
                    type = NotificationType.OPPONENT_VERIFIED,
                    challengeId = challenge.requiredId(),
                ),
            )
        }

        return SubmitOutcome(verification = saved, replacedPhotoKey = replacedPhotoKey)
    }

    /**
     * 당사자 검사 + `IN_PROGRESS` 검사. **쓰기 경로 전용.**
     *
     * 🔴 권한을 상태보다 먼저 본다. 당사자가 아닌 사람에게 *"진행 중이 아니에요"* 라고 답하면
     * 남의 챌린지 상태가 새어나간다 (`ChallengeCommandService.loadForResponse` 와 같은 이유).
     *
     * ⚠️ **교체도 이 문을 지난다.** 마감이 지나면 `IN_PROGRESS` 가 아니므로 사진을 바꿀 수 없다 —
     * *"마감 시점의 사진이 최종본"* 이라는 증거 보존이 여기에 걸려 있다.
     */
    private fun loadInProgressAsParticipant(challengeId: Long, me: Long): Challenge {
        val challenge = challengeRepository.loadAsParticipant(challengeId, me)
        if (challenge.status != ChallengeStatus.IN_PROGRESS) {
            throw OneButtonDialogException(VerificationService.MSG_NOT_IN_PROGRESS)
        }
        return challenge
    }

    /**
     * 내 `verifications` row. `IN_PROGRESS` 라면 `accept()` 가 만들어 뒀어야 한다.
     *
     * 없다면 데이터가 깨진 것이므로 조용히 만들어 덮지 않는다 — **여기서 INSERT 하면
     * "수락 없이 인증된 챌린지" 를 이 서비스가 만들어낼 수 있다.**
     */
    private fun myVerification(challengeId: Long, me: Long): Verification =
        verificationRepository.findAllByChallengeIds(listOf(challengeId))
            .firstOrNull { it.userId == me }
            ?: throw OneButtonDialogException(VerificationService.MSG_VERIFICATION_MISSING)
}

/**
 * [VerificationSubmitTransactionalWorker.submit] 의 결과.
 *
 * @param replacedPhotoKey 이 제출이 **참조를 끊은** 이전 사진 key. 교체가 아니면 null.
 *   호출자가 **커밋 후에** 지운다 ([VerificationService.submit] KDoc).
 */
data class SubmitOutcome(
    val verification: Verification,
    val replacedPhotoKey: String?,
)

/**
 * 챌린지 로드 + 당사자 검사. 순서가 의미를 만든다 — **없음 → 당사자 아님** 순이며
 * `ChallengeDetailService` 와 같다.
 *
 * 읽기 경로([VerificationService.getVerifications])와 쓰기 경로
 * ([VerificationSubmitTransactionalWorker.loadInProgressAsParticipant])가 같은 문을 쓰도록
 * 파일 스코프 함수로 둔다 — 트랜잭션 경계를 두 빈으로 가르면서 이 검사까지 복제하면
 * 한쪽만 고쳐지는 날이 온다.
 */
private fun ChallengeRepository.loadAsParticipant(challengeId: Long, me: Long): Challenge {
    val challenge = findById(challengeId)
        ?: throw OneButtonDialogException(VerificationService.MSG_NOT_FOUND)
    if (me != challenge.challengerId && me != challenge.opponentId) {
        throw SnackbarException(VerificationService.MSG_NOT_MINE)
    }
    return challenge
}

/** JPEG 매직 넘버(`FF D8 FF`) 확인. */
private fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()

/**
 * 사진 바이트의 **버전 표** — 저장소 key 의 sha256 앞 32자를 큰따옴표로 감싼 ETag 값.
 *
 * 🔴 **key 를 그대로 쓰지 않는 것이 의도다.** 계약 §0.4 가 *"photoKey 는 앱에 노출되지 않는다"*
 * 를 명문화했고, ETag 는 응답 헤더라 그대로 실으면 그 조항이 깨진다.
 *
 * key 를 재료로 삼는 이유는 [PhotoStorage.store] 가 **부를 때마다 새 key** 를 만들기 때문이다 —
 * 사진이 바뀌면 반드시 바뀌고, 안 바뀌면 절대 안 바뀐다. `verified_at` 은 이 성질이 없다
 * (같은 초 안의 두 교체가 같은 값을 낸다).
 */
private fun photoETag(key: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    return "\"" + digest.joinToString("") { "%02x".format(it) }.take(32) + "\""
}

private fun Challenge.requiredId(): Long = id ?: error("조회된 challenge 의 id 가 null 일 수 없습니다")

private fun Challenge.opponentOf(me: Long): Long = if (me == challengerId) opponentId else challengerId

/** 챌린지의 두 당사자. 사진 경로의 `{party}` 조각이 이 이름의 소문자다. */
enum class VerificationParty { CHALLENGER, OPPONENT }

/** [VerificationService.readPhoto] 의 결과. [Found] 외에는 바디가 없다. */
sealed interface PhotoReadResult {
    /**
     * HTTP 200 + 바이트.
     *
     * @param etag 이 바이트의 **버전 표** (큰따옴표 포함한 ETag 헤더 값). 사진 교체가 열리면서
     *   같은 경로가 다른 바이트를 낼 수 있게 됐으므로 컨트롤러가 이것으로 조건부 GET 을 답한다.
     *   만드는 방법과 근거는 `photoETag` KDoc.
     */
    data class Found(val bytes: ByteArray, val contentType: String, val etag: String) : PhotoReadResult {
        // ByteArray 를 담은 data class 라 equals/hashCode 를 직접 정의한다 (기본 구현은 참조 비교).
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Found) return false
            return bytes.contentEquals(other.bytes) && contentType == other.contentType && etag == other.etag
        }

        override fun hashCode(): Int =
            31 * (31 * bytes.contentHashCode() + contentType.hashCode()) + etag.hashCode()
    }

    /** 챌린지 없음 / 아직 인증 안 함 / 파일 없음. HTTP 404. */
    data object NotFound : PhotoReadResult

    /** 당사자가 아니다. HTTP 403. */
    data object Forbidden : PhotoReadResult
}

/** 양측 인증 상태. `challenger`/`opponent` 를 역할 그대로 담는다. */
data class VerificationsView(
    val challengeId: Long,
    val challenger: PartyVerificationView,
    val opponent: PartyVerificationView,
)

/**
 * 한 사람의 인증 상태.
 *
 * @param photoUrl **만료 없는 고정 상대 경로.** `VERIFIED` 가 아니거나 key 가 없으면 null.
 * @param photoDeleted 사진이 **탈퇴로 삭제됐는가.** 판정식과 세 갈래 표는
 *   [VerificationService.view] KDoc 참조.
 *
 *   🔴 **`isWithdrawn` 이 아니라 `photoDeleted` 인 것이 의도다.** 앱이 *"왜"* 가 아니라
 *   *"뭘 그릴지"* 만 받게 한다. design 이 보관함 카드에서 `isWithdrawn` 플래그를 **명시적으로
 *   거부**했다 — 받으면 앱이 특별 표시를 넣게 되고, 증거 보존 화면에서 그건 *"이 기록은 무효"*
 *   로 읽힌다. `photoDeleted` 는 **사진 자리 하나만 바꾸고 다른 데 쓸 수 없다.**
 */
data class PartyVerificationView(
    val userId: Long,
    val status: VerificationStatus,
    val photoUrl: String?,
    val verifiedAt: LocalDateTime?,
    val photoDeleted: Boolean,
)
