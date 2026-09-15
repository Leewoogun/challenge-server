package com.lwg.challenge.service.challenge

import com.lwg.challenge.core.challenge.ChallengeSchedule
import com.lwg.challenge.core.challenge.DeadlineType
import com.lwg.challenge.core.challenge.KstDeadlineCalculator
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.ReceivedChallenge
import com.lwg.challenge.domain.common.exception.OneButtonDialogException
import com.lwg.challenge.domain.contract.Contract
import com.lwg.challenge.domain.contract.ContractRepository
import com.lwg.challenge.domain.contract.SignatureValidator
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.friend.FriendshipRepository
import com.lwg.challenge.domain.friend.FriendshipStatus
import com.lwg.challenge.domain.notification.NotificationType
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationRepository
import com.lwg.challenge.domain.verification.VerificationStatus
import com.lwg.challenge.service.notification.ChallengeNotificationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 챌린지 신청 / 수락 / 거절 / 취소 비즈니스 로직 (challenge-create T-B3).
 *
 * 읽기 전용 조회는 [ActiveChallengeService] (home-feed) 가 담당하고, 이 서비스는 **쓰기 + 받은 도전장 목록**만 다룬다.
 *
 * ## 시간 축 (ADR-0010)
 * `challenges.deadline` / `created_at` 은 timezone 없는 `TIMESTAMP` 이고 프로젝트 전체가 이를 **KST 로 간주**한다.
 * 챌린지의 "하루" 도 KST 로 끊으므로 **축이 하나뿐이고 변환이 없다.**
 * 주입된 [Clock] 이 KST 라 `LocalDateTime.now(clock)` 이 곧 저장 값이다.
 *
 * ## 에러 코드
 * api-contract 확정 배분:
 * - **700 (스낵바)** — 사용자가 입력을 고치면 되는 것 + 권한 위반
 * - **705 (단일버튼 다이얼로그)** — "목록이 이미 낡았다" 는 신호 (없음 / 이미 처리됨 / 마감 경과)
 *
 * 모바일은 현재 code 를 소비하지 못하고 `message` 만 받으므로(계약 "공통 규약" 절),
 * **여기 있는 모든 메시지 문자열은 그대로 사용자에게 노출되는 UI 텍스트다.** 문구를 바꾸면 계약 위반이다.
 */
@Service
class ChallengeCommandService(
    private val challengeRepository: ChallengeRepository,
    private val friendshipRepository: FriendshipRepository,
    private val userRepository: UserRepository,
    private val verificationRepository: VerificationRepository,
    private val contractRepository: ContractRepository,
    private val clock: Clock,
    /**
     * 알림 이벤트 발행 (push-fcm T-B5).
     *
     * 🔴 **여기서 발송하지 않고 이벤트만 던진다.** 발송을 이 트랜잭션에 묶으면 FCM 실패가
     * 챌린지 생성·수락·거절을 롤백시킨다 — *"알림이 안 갔다"* 는 불편이지만 *"챌린지가 안 걸렸다"* 는
     * 기능 실패다 (계약 §0.3). `NotificationDispatcher` 가 `AFTER_COMMIT` 으로 받는다.
     */
    private val eventPublisher: ApplicationEventPublisher,
) {

    // ─── 1. 신청 ─────────────────────────────────────────────────

    /**
     * 친구에게 챌린지를 신청한다. `PENDING` row 1건을 만든다.
     *
     * 검증 순서는 **싼 것 → 비싼 것**: 본인 여부(무비용) → 길이(무비용) → 상대 존재(1 쿼리) →
     * 친구 여부(1 쿼리) → 중복(1 쿼리). 앞에서 걸리면 뒤 쿼리를 아예 안 친다.
     *
     * `opponent_mission` 은 null 로 INSERT 된다 — 상대가 수락하며 채운다 (V5 마이그레이션 선행).
     */
    @Transactional
    fun create(
        me: Long,
        opponentId: Long,
        myMission: String,
        betContent: String,
        deadlineType: DeadlineType,
        signature: String,
    ): Challenge {
        if (me == opponentId) {
            throw SnackbarException(MSG_SELF_CHALLENGE)
        }

        val mission = requireLength(myMission, MSG_MISSION_LENGTH)
        val bet = requireLength(betContent, MSG_BET_LENGTH)
        // 서명 검증은 DB 를 치기 전에 — 입력 문제로 거부될 요청에 쿼리 비용을 쓰지 않는다.
        SignatureValidator.validate(signature)

        val opponent = userRepository.findById(opponentId)
            ?: throw SnackbarException(MSG_OPPONENT_NOT_FOUND)
        if (opponent.status != UserStatus.ACTIVE) {
            // SUSPENDED / DELETED 사용자의 존재 자체를 알려주지 않는다 (friends 검색과 동일 방침).
            throw SnackbarException(MSG_OPPONENT_NOT_FOUND)
        }

        if (!isFriend(me, opponentId)) {
            throw SnackbarException(MSG_NOT_FRIEND)
        }

        val now: LocalDateTime = nowKst()
        val schedule: ChallengeSchedule = KstDeadlineCalculator.calculate(deadlineType, now)

        // 사전 검사 — 양방향. 상대가 나에게 이미 건 경우도 중복이다.
        rejectIfDuplicated(me = me, opponentId = opponentId, schedule = schedule)

        val toInsert = Challenge(
            id = null,
            challengerId = me,
            opponentId = opponentId,
            challengerMission = mission,
            opponentMission = null,
            betContent = bet,
            challengeDate = schedule.challengeDate,
            deadline = schedule.deadline,
            status = ChallengeStatus.PENDING,
            result = null,
            createdAt = now,
            completedAt = null,
            // 신청 시점에는 기권이 있을 수 없다 — 기권은 IN_PROGRESS 에서만 가능하다.
            forfeitedBy = null,
        )

        // 사전 검사 통과 후에도 동시 INSERT race 가 남는다 — READ COMMITTED 에서 상대 트랜잭션의
        // 미커밋 row 는 보이지 않기 때문. V5 의 부분 유니크 인덱스(uq_challenges_active_pair_date)가
        // 최종 방어선이고, saveAndFlush 로 즉시 flush 해야 그 위반이 이 catch 안에서 잡힌다.
        val created = try {
            challengeRepository.saveAndFlush(toInsert)
        } catch (e: DataIntegrityViolationException) {
            throw SnackbarException(MSG_DUPLICATE_OWN)
        }

        // 🔴 계약서를 같은 트랜잭션에서 만든다 — 챌린저 서명만 채우고 isFinalized=false.
        // 챌린지는 있는데 계약서가 없는 상태가 생기면 상대가 맹세 화면에서 볼 게 없다.
        val challengeId = created.id ?: error("저장된 challenge 의 id 가 null 일 수 없습니다")
        contractRepository.save(
            Contract(
                id = null,
                challengeId = challengeId,
                content = ContractContent.render(
                    challengerNickname = userRepository.findById(me)?.nickname ?: UNKNOWN_NICKNAME,
                    challengerMission = mission,
                    opponentNickname = opponent.nickname,
                    // 아직 수락 전이라 상대 미션이 없다 → "(수락 시 기재)".
                    // 수락+서명 시점에 실제 미션으로 **재렌더**한다 (ContractContent KDoc).
                    opponentMission = null,
                    betContent = bet,
                    challengeDate = schedule.challengeDate,
                ),
                challengerSignatureData = signature,
                challengerSignedAt = now,
                opponentSignatureData = null,
                opponentSignedAt = null,
                isFinalized = false,
                createdAt = now,
            ),
        )

        publishNotification(
            recipientId = opponentId,
            actorId = me,
            type = NotificationType.CHALLENGE_REQUEST,
            challengeId = challengeId,
        )

        return created
    }

    // ─── 2. 받은 도전장 목록 ──────────────────────────────────────

    /**
     * 내가 `opponent` 인 `PENDING` 챌린지 중 마감 미경과분을 `created_at DESC` 로 반환. 없으면 빈 리스트.
     *
     * 마감 지난 row 는 DB 에서 `PENDING` 인 채로 남고 응답에서만 빠진다 (lazy expiry).
     */
    @Transactional(readOnly = true)
    fun listReceived(me: Long): List<ReceivedChallenge> =
        challengeRepository.findReceivedPending(me, nowKst())

    // ─── 3. 수락 ─────────────────────────────────────────────────

    /**
     * 받은 도전장을 수락하고 본인 미션을 입력한다.
     *
     * **단일 트랜잭션**으로 두 가지가 함께 일어난다:
     * 1. `challenges` UPDATE — `status = IN_PROGRESS`, `opponent_mission = 입력값`
     * 2. `verifications` INSERT 2건 — challenger / opponent 각각 `PENDING`
     *
     * `CONTRACT_SIGNING` 을 건너뛰고 바로 `IN_PROGRESS` 로 가는 것은 spec 스코프 결정 5.
     * 영혼의 맹세 feature 가 나중에 두 상태 사이에 끼어든다.
     */
    @Transactional
    fun accept(me: Long, challengeId: Long, myMission: String, signature: String): Challenge {
        val challenge = loadForResponse(challengeId, me, expectedActor = Actor.OPPONENT)
        val mission = requireLength(myMission, MSG_MISSION_LENGTH)
        SignatureValidator.validate(signature)

        // 🔴 계약서를 먼저 완결시킨다. `is_finalized = true` 가 된 시점에만 IN_PROGRESS 로 간다
        // (spec 수용 기준). 원자 요청이라 둘은 항상 함께 일어난다.
        val contract = contractRepository.findByChallengeId(challengeId)
            ?: throw OneButtonDialogException(MSG_CONTRACT_MISSING)
        if (contract.isFinalized) {
            // "무를 수 없다" — 재서명 거부. 대개는 위 status 가드가 먼저 막지만 2차 방어선이다.
            throw OneButtonDialogException(MSG_ALREADY_OATHED)
        }
        val signedAt = nowKst()
        // 🔴 본문을 **여기서 한 번 더 렌더**한다. 신청 시점 본문은 상대 미션이 "(수락 시 기재)" 인
        // 미완성 스냅샷이고, 그걸 그대로 두면 완성된 계약서에 한쪽 약속이 영영 비어 있다.
        // 박제 대상은 완성된 약속이어야 한다 — 그 시점이 is_finalized 다 (ContractContent KDoc).
        // 이 뒤로는 재렌더가 없다: 재서명이 위에서 막히므로 이 경로는 계약당 한 번만 실행된다.
        val parties = userRepository.findAllByIds(setOf(challenge.challengerId, challenge.opponentId))
        contractRepository.save(
            contract.copy(
                content = ContractContent.render(
                    challengerNickname = parties[challenge.challengerId]?.nickname ?: UNKNOWN_NICKNAME,
                    challengerMission = challenge.challengerMission,
                    opponentNickname = parties[challenge.opponentId]?.nickname ?: UNKNOWN_NICKNAME,
                    opponentMission = mission,
                    betContent = challenge.betContent,
                    challengeDate = challenge.challengeDate,
                ),
                opponentSignatureData = signature,
                opponentSignedAt = signedAt,
                isFinalized = true,
            ),
        )

        val accepted = challengeRepository.save(
            challenge.copy(
                status = ChallengeStatus.IN_PROGRESS,
                opponentMission = mission,
            ),
        )

        val createdAt = nowKst()
        // uq_verifications_challenge_user 가 중복 수락의 안전망. saveAllAndFlush 라 위반이 여기서 잡힌다.
        try {
            verificationRepository.saveAllAndFlush(
                listOf(
                    pendingVerification(challengeId, challenge.challengerId, createdAt),
                    pendingVerification(challengeId, challenge.opponentId, createdAt),
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // 동시에 두 번 수락이 들어온 경우. 트랜잭션이 롤백되어 status 변경도 함께 되돌아간다.
            throw OneButtonDialogException(MSG_ALREADY_HANDLED)
        }

        // 수락을 알리는 대상은 **신청자**다. 수락한 본인(me)이 아니다.
        publishNotification(
            recipientId = challenge.challengerId,
            actorId = me,
            type = NotificationType.CHALLENGE_ACCEPTED,
            challengeId = challengeId,
        )

        return accepted
    }

    // ─── 4. 거절 ─────────────────────────────────────────────────

    /**
     * 받은 도전장을 거절한다. `REJECTED` 로 UPDATE — row 는 보존한다.
     *
     * 거절된 챌린지는 `challenge_date` 중복 검사 대상(PENDING/IN_PROGRESS)에서 빠지므로,
     * 같은 날 같은 상대에게 다시 도전할 수 있다.
     */
    @Transactional
    fun reject(me: Long, challengeId: Long): Challenge {
        val challenge = loadForResponse(challengeId, me, expectedActor = Actor.OPPONENT)
        val rejected = challengeRepository.save(challenge.copy(status = ChallengeStatus.REJECTED))

        // 거절도 **신청자**에게 알린다. 기획서에 없던 공백이었다 —
        // 신청자는 상대가 거절했는지 알 방법이 홈 재방문뿐이었다.
        publishNotification(
            recipientId = challenge.challengerId,
            actorId = me,
            type = NotificationType.CHALLENGE_REJECTED,
            challengeId = challengeId,
        )

        return rejected
    }

    // ─── 5. 취소 (챌린저가 보낸 PENDING 회수) ─────────────────────

    /**
     * 챌린저가 자신이 보낸 `PENDING` 챌린지를 취소한다. **물리 삭제**.
     *
     * `challenge_states` 에 `CANCELED` 가 없어 새 상태 도입을 피했다 (friends 2차 요청 취소와 동일한 선택).
     * `PENDING` 이라 `verifications` 참조 row 가 아직 없으므로 FK 정리가 필요 없다.
     *
     * **마감 경과를 막지 않는다** — 마감이 지난 내 도전장을 치우는 것은 사용자에게 유익하고,
     * api-contract §5 의 에러 표에도 마감 항목이 없다.
     */
    @Transactional
    fun cancel(me: Long, challengeId: Long) {
        val challenge = challengeRepository.findById(challengeId)
            ?: throw OneButtonDialogException(MSG_NOT_FOUND)
        if (challenge.challengerId != me) {
            throw SnackbarException(MSG_NOT_MY_SENT)
        }
        if (challenge.status != ChallengeStatus.PENDING) {
            throw OneButtonDialogException(MSG_ALREADY_HANDLED)
        }
        // 🔴 contract 를 **먼저** 지운다. `contracts_challenge_id_fkey` 에 ON DELETE 절이 없어
        // 순서를 지키지 않으면 FK 위반 → HTTP 500 이다 (soul-oath api-contract §0.4).
        // CASCADE 를 쓰지 않는 이유: "무를 수 없는 약속" 이 컨셉인데 CASCADE 는 다른 이유로
        // challenge 를 지울 때도 맹세를 조용히 함께 지운다. 명시적 삭제는 코드에 순서가 보인다.
        contractRepository.deleteByChallengeId(challengeId)
        challengeRepository.deleteById(challengeId)
    }

    // ─── 내부 helper ─────────────────────────────────────────────

    private enum class Actor { OPPONENT }

    /**
     * 수락/거절 공통 로드 + 검증. 네 가지를 순서대로 본다.
     *
     * 순서가 의미를 만든다: **권한을 상태보다 먼저** 본다. 당사자가 아닌 사람에게
     * "이미 처리된 챌린지예요" 라고 알려주면 남의 챌린지 상태가 새어나간다.
     */
    private fun loadForResponse(challengeId: Long, me: Long, expectedActor: Actor): Challenge {
        val challenge = challengeRepository.findById(challengeId)
            ?: throw OneButtonDialogException(MSG_NOT_FOUND)

        val actorId = when (expectedActor) {
            Actor.OPPONENT -> challenge.opponentId
        }
        if (actorId != me) {
            throw SnackbarException(MSG_NOT_MY_RECEIVED)
        }
        if (challenge.status != ChallengeStatus.PENDING) {
            throw OneButtonDialogException(MSG_ALREADY_HANDLED)
        }
        if (KstDeadlineCalculator.isExpired(challenge.deadline, nowKst())) {
            throw OneButtonDialogException(MSG_EXPIRED)
        }
        return challenge
    }

    /**
     * 양방향 중복 검사. 걸리면 **방향에 따라 다른 문구**로 던진다.
     *
     * 상대가 나에게 건 PENDING 때문에 막힌 경우 "진행 중인 챌린지가 있어요" 라고 하면,
     * 사용자가 홈의 진행 중 목록을 봐도 아무것도 없어 혼란스럽다 (PENDING 은 `/challenges/active` 에 안 잡힌다).
     * 그래서 이 경우만 "받은 도전장을 확인해보세요" 로 안내한다 (2026-07-28 mobile-dev 요청).
     */
    private fun rejectIfDuplicated(me: Long, opponentId: Long, schedule: ChallengeSchedule) {
        val conflicts = challengeRepository.findConflicting(
            userA = me,
            userB = opponentId,
            challengeDate = schedule.challengeDate,
            statuses = CONFLICTING_STATUSES,
        )
        if (conflicts.isEmpty()) return

        val reversePending = conflicts.any {
            it.challengerId == opponentId && it.status == ChallengeStatus.PENDING
        }
        throw SnackbarException(if (reversePending) MSG_DUPLICATE_REVERSE else MSG_DUPLICATE_OWN)
    }

    /** `friendships` 에 어느 방향이든 ACCEPTED row 가 있으면 친구. */
    private fun isFriend(me: Long, opponentId: Long): Boolean =
        friendshipRepository.findBetween(me, opponentId)
            .any { it.status == FriendshipStatus.ACCEPTED }

    /**
     * trim 후 1..100 자 검증. 통과하면 **trim 된 값**을 돌려준다 (저장되는 것은 trim 된 값).
     *
     * 길이는 JVM `String.length` = UTF-16 code unit 기준. 한글은 1자=1 이라 모바일의
     * `maxLength=100` 하드캡과 자동으로 일치하고, BMP 밖 이모지만 2 로 센다 (계약 명시).
     */
    private fun requireLength(raw: String, message: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TEXT_LENGTH) {
            throw SnackbarException(message)
        }
        return trimmed
    }

    /**
     * 알림 이벤트를 발행한다. **트랜잭션 안에서 부르지만 발송은 커밋 후에 일어난다** (계약 §0.3).
     *
     * 🔴 **이 호출이 실패해도 챌린지는 성립해야 한다.** `publishEvent` 자체는 리스너를 등록만 하고
     * 즉시 실행하지 않으므로(`AFTER_COMMIT`) 여기서 FCM 이나 DB 를 건드리지 않는다 — 즉 이 줄은
     * 트랜잭션에 위험을 더하지 않는다. 실제 처리 중 터지는 예외는
     * [com.lwg.challenge.service.notification.NotificationDispatcher] 가 삼킨다.
     *
     * 롤백되면 커밋이 없으므로 리스너가 아예 실행되지 않는다 — **롤백 시 미발송이 공짜로 성립한다.**
     */
    private fun publishNotification(
        recipientId: Long,
        actorId: Long,
        type: NotificationType,
        challengeId: Long,
    ) {
        eventPublisher.publishEvent(
            ChallengeNotificationEvent(
                recipientId = recipientId,
                actorId = actorId,
                type = type,
                challengeId = challengeId,
            ),
        )
    }

    private fun pendingVerification(challengeId: Long, userId: Long, createdAt: LocalDateTime) =
        Verification(
            id = null,
            challengeId = challengeId,
            userId = userId,
            status = VerificationStatus.PENDING,
            photoUrl = null,
            verifiedAt = null,
            createdAt = createdAt,
        )

    /**
     * 현재 KST 벽시계 시각. `clock` 빈이 KST 이므로 그대로 DB 저장 축과 일치한다 (ADR-0010).
     *
     * 예전에는 `clock.instant().atZone(UTC)` 로 UTC 벽시계를 만들었는데, 그게 레포의 나머지
     * 코드(엔티티 기본값 = JVM 기본 KST)와 9시간 어긋난 원인이었다.
     */
    private fun nowKst(): LocalDateTime = LocalDateTime.now(clock)

    companion object {
        /** 미션 / 내기 최대 길이 (trim 후). api-contract 확정값. */
        const val MAX_TEXT_LENGTH = 100

        /** "살아있는" 챌린지 — 이 상태의 챌린지가 있으면 같은 쌍·같은 날짜로 새로 못 건다. */
        private val CONFLICTING_STATUSES: Set<ChallengeStatus> =
            setOf(ChallengeStatus.PENDING, ChallengeStatus.IN_PROGRESS)

        // ── 사용자 노출 문구 (api-contract 확정. 변경 시 계약 + 슬라이스 테스트 동시 수정) ──
        const val MSG_SELF_CHALLENGE = "자기 자신에게는 챌린지를 걸 수 없어요"
        const val MSG_MISSION_LENGTH = "미션은 1자 이상 100자 이하로 입력해주세요"
        const val MSG_BET_LENGTH = "내기 내용은 1자 이상 100자 이하로 입력해주세요"
        const val MSG_OPPONENT_NOT_FOUND = "상대를 찾을 수 없어요"
        const val MSG_NOT_FRIEND = "친구에게만 챌린지를 걸 수 있어요"
        const val MSG_DUPLICATE_OWN = "오늘은 이 친구와 이미 챌린지가 있어요"
        const val MSG_DUPLICATE_REVERSE = "이 친구가 이미 도전장을 보냈어요. 받은 도전장을 확인해보세요"
        const val MSG_NOT_FOUND = "챌린지를 찾을 수 없어요"
        const val MSG_ALREADY_HANDLED = "이미 처리된 챌린지예요"
        const val MSG_EXPIRED = "마감이 지난 챌린지예요"
        const val MSG_NOT_MY_RECEIVED = "내가 받은 도전장이 아니에요"
        const val MSG_NOT_MY_SENT = "내가 보낸 도전장이 아니에요"
        const val MSG_ALREADY_OATHED = "이미 맹세를 마친 챌린지예요"
        const val MSG_CONTRACT_MISSING = "계약서를 찾을 수 없어요"
        private const val UNKNOWN_NICKNAME = "(알 수 없음)"
    }
}
