package com.lwg.challenge.service.user

import com.lwg.challenge.domain.auth.KakaoServerException
import com.lwg.challenge.domain.auth.KakaoTokenInvalidException
import com.lwg.challenge.domain.auth.KakaoUnlinkPort
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.common.exception.DialogException
import com.lwg.challenge.domain.friend.FriendshipRepository
import com.lwg.challenge.domain.notification.NotificationRepository
import com.lwg.challenge.domain.photo.PhotoStorage
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.domain.verification.VerificationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 회원탈퇴 (mypage T-B2).
 *
 * ## 탈퇴 = 익명화 + 기록 보존 + 사진 삭제
 *
 * row 를 지우지 않는다. 상대의 챌린지·계약서·서명·결과가 FK 로 `users` row 를 가리키므로
 * 지우면 **탈퇴하지 않은 사람의 기록까지 무너진다.** 대신 개인정보만 지운다.
 *
 * | 대상 | 처리 |
 * |---|---|
 * | `users` 개인정보 | 익명화 (`UserRepository.anonymizeForWithdrawal`) |
 * | 카카오 연결 | unlink ([KakaoUnlinkPort]) |
 * | `friendships` | 물리 삭제 |
 * | 탈퇴자가 **보낸**(challenger) `PENDING` 챌린지 | 🔴 물리 삭제 (T-B4) |
 * | 탈퇴자가 **받은**(opponent) `PENDING` 챌린지 | 그대로 둔다 — 수락될 수 없어 자연 만료 |
 * | `notifications` (수신함) | 🔴 물리 삭제 (T-B4) |
 * | `COMPLETED`/`IN_PROGRESS` 챌린지·계약서·서명·결과·`user_stats`·`friend_records`·`taunt_messages` | 🔴 보존 |
 * | `verifications` **row** | 🔴 보존 (`status`/`verified_at` 그대로) |
 * | 인증 사진 **파일** + `photo_url`(=key) | 삭제 / NULL |
 *
 * ### 왜 `PENDING` 만 예외적으로 지우는가 (mypage T-B4)
 *
 * 보존 원칙의 근거는 *"그 기록이 상대의 것이기도 하다"* 인데, `PENDING` 에는 계약서·서명·사진·
 * 결과가 **하나도 없다** — 보존할 증거가 0 이다. 반면 남겨 두면 상대가 탈퇴자의 도전장을 수락해
 * **결과가 이미 정해진 챌린지**(탈퇴자는 로그인이 불가해 인증을 못 올린다)를 새로 시작한다.
 * 처리는 챌린저가 직접 취소한 것과 정확히 같다. 방향이 반대인 *"받은 `PENDING`"* 은 아무도
 * 수락할 수 없어 자연 만료되므로 건드리지 않는다 (`ChallengeRepository.deletePendingSentBy`).
 *
 * ### 왜 `notifications` 는 보존이 아닌가 (mypage T-B4)
 *
 * 수신함은 **본인만 읽는 개인 데이터**라 양자 기록이 아니고 **개인정보 삭제 축**에 속한다
 * (pm-lead 판정). 아무도 읽을 수 없는 채로 *"누가 나에게 무엇을 했는지"* 를 남길 이유가 없다
 * (`NotificationRepository.deleteAllByUserId`).
 *
 * ## 🔴 실행 순서가 이 클래스의 전부다
 *
 * ```
 * 1. [tx 밖]  user 조회. 없거나 이미 DELETED → 멱등 성공으로 조용히 반환
 * 2. [tx 밖]  카카오 unlink        ← 🔴 DB 보다 먼저
 * 3. [tx 안]  사진 key 수집 → verifications.photo_url NULL → 보낸 PENDING 삭제
 *             → notifications 삭제 → friendships 삭제 → users 익명화
 * 4. [커밋 후] 사진 파일 삭제
 * ```
 *
 * ### 왜 unlink 가 DB 보다 먼저인가
 *
 * 두 순서의 **실패 결과가 비대칭**이다.
 *
 * - **DB 먼저 → unlink 실패**: 계정은 이미 **되돌릴 수 없게** 익명화됐는데 카카오 연결은
 *   살아 있다. 게다가 `kakao_id` 를 지워버려서 **재시도할 대상조차 잃는다.** 복구 불가다.
 * - **unlink 먼저 → DB 실패**: 카카오 연결만 끊기고 row 는 그대로 남는다. 사용자가 다시
 *   로그인하면 카카오가 재동의를 받고 **같은 user id** 를 주므로 같은 row 로 돌아온다.
 *   탈퇴를 다시 누르면 된다. **복구 가능하고 데이터 손실이 0 이다.**
 *
 * 그래서 unlink 먼저가 엄격하게 안전하다. (재시도 시 unlink 는 `-101` 로 이미 연결이 없다고
 * 답하는데, [KakaoUnlinkPort] 가 그걸 **성공으로 삼키므로** 2회차가 막히지 않는다.)
 *
 * ### 왜 unlink 실패는 200 이 아니라 에러인가
 *
 * 로그아웃과 다르다. `AuthService.logout` 은 *"언제나 성공해야 하는 연산"* 이라 실패를 삼키지만,
 * 탈퇴는 **카카오 연결 해제가 이행되지 않았는데 성공이라고 답하면 안 된다.** 사용자는 탈퇴가
 * 끝났다고 믿는데 우리 앱은 여전히 그의 카카오 계정에 연결돼 있는 상태가 남는다.
 * [DialogException](code=701) 으로 재시도를 안내한다.
 *
 * ### 왜 사진 삭제는 커밋 후인가
 *
 * 파일 삭제는 트랜잭션이 아니다. tx 안에서 지우면 **롤백돼도 파일은 돌아오지 않는다** —
 * row 는 살아 있는데 사진만 사라진 상태가 남는다. 커밋 후로 미루면 최악이 **고아 파일**
 * (아무 row 도 가리키지 않는 파일)뿐이고, 그건 읽히지 않으므로 무해하다.
 * 파일 삭제 실패는 **삼키고 WARN 만 남긴다** — 이미 커밋된 탈퇴를 되돌릴 수 없다.
 *
 * ## 🔴 왜 빈이 둘인가 — self-invocation 함정
 *
 * `@Transactional` 은 **프록시**로 동작한다. 같은 클래스 안에서 `this.anonymizeInTx()` 를
 * 부르면 프록시를 타지 않아 **트랜잭션이 아예 열리지 않고**, 다섯 개의 벌크 UPDATE/DELETE 가
 * 각각 따로 커밋된다. 그러면 중간 실패 시 *"친구는 지워졌는데 익명화는 안 된"* 상태가 남는다.
 * 컴파일도 되고 테스트도 통과하는데 운영에서만 깨지는 종류의 버그라 구조로 막는다 —
 * 트랜잭션 경계를 [WithdrawalTransactionalWorker] 라는 **별도 빈**으로 빼서 호출이 반드시
 * 프록시를 지나게 한다.
 *
 * ## 멱등성
 *
 * 이미 `DELETED` 인 사용자가 다시 부르면 **아무것도 하지 않고 성공**한다. 이건 이론적 배려가
 * 아니다 — access token 이 최대 1시간 살아 있어 **탈퇴 직후 재요청이 실제로 가능**하고,
 * `JwtAuthenticationFilter` 는 status 를 보지 않는다.
 */
@Service
class WithdrawalService(
    private val userRepository: UserRepository,
    private val kakaoUnlinkPort: KakaoUnlinkPort,
    private val worker: WithdrawalTransactionalWorker,
    private val photoStorage: PhotoStorage,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(WithdrawalService::class.java)

    /**
     * [me] 를 탈퇴시킨다.
     *
     * ⚠️ **이 메서드에는 `@Transactional` 이 없고, 그게 의도다.** 붙이면 3단계의 커밋이 이
     * 메서드가 끝날 때까지 미뤄져 4단계(사진 삭제)가 **커밋 전**에 실행된다 — 위 KDoc 이
     * 막으려는 바로 그 상태다.
     *
     * @throws DialogException 카카오 연결 해제에 실패했을 때 (code=701)
     */
    fun withdraw(me: Long) {
        val user = userRepository.findById(me)
        if (user == null) {
            // 토큰은 유효한데 row 가 없다. 이미 정리된 계정이므로 할 일이 없다 —
            // 여기서 401/404 를 던지면 앱이 탈퇴 화면에서 빠져나오지 못한다.
            log.info("탈퇴 요청: 대상 user 가 없다. 멱등 성공으로 처리한다. userId={}", me)
            return
        }
        if (user.status == UserStatus.DELETED) {
            log.info("탈퇴 요청: 이미 탈퇴한 사용자다. 멱등 성공으로 처리한다. userId={}", me)
            return
        }

        // ── 2. 카카오 unlink — 반드시 DB 보다 먼저 ──
        // kakaoId 가 null 인 정상 계정은 없지만(탈퇴자만 null), 위에서 DELETED 를 걸렀는데도
        // null 이라면 데이터가 깨진 것이다. 그때 unlink 할 대상이 없는 것은 사실이므로
        // 익명화를 막지 않고 넘어간다 — 여기서 멈추면 그 계정은 영원히 탈퇴하지 못한다.
        val kakaoId = user.kakaoId
        if (kakaoId == null) {
            log.warn("탈퇴 요청: kakao_id 가 없는 비-DELETED 계정이다. unlink 를 건너뛴다. userId={}", me)
        } else {
            unlinkOrFail(me, kakaoId)
        }

        // ── 3. DB 익명화 (한 트랜잭션) ──
        val photoKeys = worker.anonymize(me, LocalDateTime.now(clock))

        // ── 4. 커밋 후 사진 파일 삭제 ──
        deletePhotosQuietly(me, photoKeys)

        log.info("회원탈퇴 완료: userId={}, 삭제 대상 사진 {}건", me, photoKeys.size)
    }

    /**
     * 카카오 연결 해제. 실패는 **탈퇴 실패**로 올린다.
     *
     * 두 예외를 같은 문구로 합치는 것이 의도다 — 사용자가 할 수 있는 일은 어느 쪽이든
     * *"잠시 후 다시 시도"* 하나뿐이고, admin key 가 틀렸다는 사실은 사용자가 아니라
     * **로그가 알아야 할 정보**다.
     */
    private fun unlinkOrFail(me: Long, kakaoId: Long) {
        try {
            kakaoUnlinkPort.unlink(kakaoId)
        } catch (e: KakaoTokenInvalidException) {
            log.warn("카카오 연결 해제 실패 (인증 — admin key 를 확인하라): userId={}, cause={}", me, e.message)
            throw DialogException(MSG_WITHDRAWAL_FAILED)
        } catch (e: KakaoServerException) {
            log.warn("카카오 연결 해제 실패 (서버/네트워크): userId={}, cause={}", me, e.message)
            throw DialogException(MSG_WITHDRAWAL_FAILED)
        }
    }

    /**
     * 사진 파일 삭제. **한 건의 실패가 나머지와 탈퇴 전체를 막지 않는다.**
     *
     * 🔴 여기서 예외가 새면 **이미 커밋된 탈퇴가 실패로 응답된다.** 사용자는 재시도할 텐데
     * 그때는 `status = DELETED` 라 멱등 분기로 빠져 사진은 영영 지워지지 않는다.
     * 남는 것이 고작 고아 파일이므로 삼키고 기록만 남기는 쪽이 옳다.
     */
    private fun deletePhotosQuietly(me: Long, photoKeys: List<String>) {
        photoKeys.forEach { key ->
            runCatching { photoStorage.delete(key) }
                .onFailure { e ->
                    log.warn("탈퇴 사진 삭제 실패 (고아 파일로 남는다): userId={}, key={}, cause={}", me, key, e.message)
                }
        }
    }

    companion object {
        /**
         * 🔴 **NOT NULL 을 유지하기 위한 값이다.** 상대의 지난 챌린지 상세·보관함·홈 카드가
         * 닉네임을 non-null 로 받으므로 빈 문자열이나 NULL 로 두면 그 응답들이 깨진다.
         * 모바일에 그대로 노출되는 **UI 텍스트**다.
         */
        const val ANONYMIZED_NICKNAME = "탈퇴한 사용자"

        /** 사용자 노출 문구. 변경 시 계약 + 테스트 동시 수정. */
        const val MSG_WITHDRAWAL_FAILED = "일시적인 문제로 탈퇴하지 못했어요. 잠시 후 다시 시도해주세요"
    }
}

/**
 * [WithdrawalService] 의 **트랜잭션 경계**만 담당하는 협력 빈 (mypage T-B2).
 *
 * 🔴 **별도 빈인 이유는 하나뿐이다 — self-invocation 이 프록시를 타지 않기 때문이다.**
 * 이 메서드가 `WithdrawalService` 안에 있었다면 `this.anonymize(...)` 호출에서 `@Transactional`
 * 이 **조용히 무시되고** 다섯 개의 벌크 쿼리가 따로 커밋된다. 상세 근거는 [WithdrawalService] KDoc.
 *
 * 그러니 이 클래스를 "불필요한 래퍼" 로 보고 [WithdrawalService] 안으로 합치지 마라.
 * 합치는 순간 트랜잭션이 사라지고, 그 사실은 **테스트에서 드러나지 않는다.**
 */
@Service
class WithdrawalTransactionalWorker(
    private val userRepository: UserRepository,
    private val friendshipRepository: FriendshipRepository,
    private val verificationRepository: VerificationRepository,
    private val challengeRepository: ChallengeRepository,
    private val notificationRepository: NotificationRepository,
) {

    private val log = LoggerFactory.getLogger(WithdrawalTransactionalWorker::class.java)

    /**
     * DB 쪽 탈퇴 처리를 한 트랜잭션에서 끝내고, **지워야 할 사진 key 목록**을 돌려준다.
     *
     * 🔴 **순서가 중요하다: key 수집이 가장 먼저다.** `photo_url` 을 NULL 로 민 뒤에는
     * 어떤 파일을 지워야 하는지 알아낼 방법이 없다.
     *
     * 나머지 네 쿼리(PENDING 삭제 → 수신함 삭제 → friendships 삭제 → 익명화)는 서로 독립이라
     * **순서 자체에 의미가 없다** — 같은 트랜잭션이라 다 되거나 다 안 된다. 읽는 사람이
     * *"개인정보를 지우고 마지막에 계정을 닫는다"* 로 읽도록 배열해 둔 것뿐이다.
     *
     * key 를 반환해 호출자가 **커밋 후에** 파일을 지우게 하는 것도 의도다. 여기서 직접
     * 지우면 롤백돼도 파일이 돌아오지 않는다 ([WithdrawalService] KDoc).
     *
     * @return 삭제 대상 사진 key. 아직 인증하지 않은(PENDING) row 만 있으면 빈 리스트.
     */
    @Transactional
    fun anonymize(userId: Long, now: LocalDateTime): List<String> {
        val photoKeys = verificationRepository.findAllByUserId(userId)
            .mapNotNull { it.photoUrl }

        // verifications row 는 보존한다 — status/verified_at 은 상대의 결과를 성립시키는 근거다.
        val clearedPhotos = verificationRepository.clearPhotoKeysByUserId(userId)

        // 내가 보낸 PENDING 만 지운다. 받은 PENDING 은 수락될 수 없어 자연 만료된다 —
        // 두 방향이 다른 이유는 ChallengeRepository.deletePendingSentBy KDoc.
        val deletedPending = challengeRepository.deletePendingSentBy(userId)

        // 수신함은 본인 전용 개인 데이터라 "기록 보존" 의 대상이 아니다
        // (NotificationRepository.deleteAllByUserId KDoc).
        val deletedNotifications = notificationRepository.deleteAllByUserId(userId)

        val deletedFriendships = friendshipRepository.deleteAllByUserId(userId)
        val anonymized = userRepository.anonymizeForWithdrawal(
            userId = userId,
            anonymizedNickname = WithdrawalService.ANONYMIZED_NICKNAME,
            now = now,
        )

        log.info(
            "탈퇴 익명화: userId={}, users {}행, friendships {}행 삭제, verifications {}행 key 제거, " +
                "보낸 PENDING 챌린지 {}행 삭제, notifications {}행 삭제",
            userId,
            anonymized,
            deletedFriendships,
            clearedPhotos,
            deletedPending,
            deletedNotifications,
        )

        return photoKeys
    }
}
