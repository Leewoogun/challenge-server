package com.lwg.challenge.service.challenge

import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeResult
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.Outcome
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.BusinessException
import com.lwg.challenge.domain.notification.NotificationType
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationStatus
import com.lwg.challenge.service.notification.ChallengeNotificationEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [ChallengeForfeitService] 단위 테스트 (challenge-forfeit T-B5).
 *
 * 커버하는 spec §3 수용 기준:
 * - `IN_PROGRESS` + 당사자 + **내 인증 없음** 셋이 다 참일 때만 성공한다
 * - 🔴 기권 즉시 `COMPLETED` + `result` 확정 — 자정을 기다리지 않는다
 * - 🔴 **상대의 인증 여부와 무관하게 상대 승** (두 케이스 각각)
 * - 🔴 전적이 기권 경로에서도 갱신된다 — **숫자로** 검증
 * - 🔴 `FriendRecord` 양방향 갱신 = 배치와 같은 함수를 탔음의 증거
 * - 🔴 자정 배치를 다시 돌려도 전적이 **두 번 오르지 않는다**
 * - 상대에게 `CHALLENGE_FORFEITED` 이벤트가 **정확히 1건**
 * - 거부 4종의 code 와 문구
 *
 * ## ⚠️ 이 테스트가 **덮지 못하는 것**
 *
 * 🔴 **진짜 동시성.** [FakeChallenges] 에는 트랜잭션도 행 잠금도 없어
 * `findByIdForUpdate` 가 `findById` 와 같다. 여기서 검증되는 것은 *"두 번째 요청이
 * `IN_PROGRESS` 게이트에 걸린다"* 는 **순차** 동작뿐이고, 동시 요청의 직렬화는
 * `@Lock(PESSIMISTIC_WRITE)` 와 DB 가 보장한다 (`ChallengeRepository.findByIdForUpdate` KDoc).
 *
 * 트랜잭션 경계도 못 본다 — `judge()` 와 `forfeited_by` 저장이 **한 트랜잭션**이라는 것은
 * `@Transactional` 이 붙어 있다는 구조가 보장한다 ([ChallengeJudgementServiceTest] 와 같은 한계).
 */
class ChallengeForfeitServiceTest {

    private val challenges = FakeChallenges()
    private val verifications = FakeVerifications()
    private val userRecords = FakeUserRecords()
    private val friendRecords = FakeFriendRecords()
    private val events = RecordingForfeitEventPublisher()

    /** 마감(2026-08-25 00:00 KST) 하루 전 오후 — **기권은 마감 전에 일어난다.** */
    private val clock = Clock.fixed(Instant.parse("2026-08-24T05:00:00Z"), KstTime.ZONE)
    private val now = LocalDateTime.now(clock)

    private val judgementService = ChallengeJudgementService(
        challenges,
        verifications,
        userRecords,
        friendRecords,
        clock,
    )

    private val service = ChallengeForfeitService(
        challenges,
        verifications,
        judgementService,
        events,
    )

    private companion object {
        const val CHALLENGE_ID = 7L
        const val CHALLENGER = 1L
        const val OPPONENT = 2L
        const val STRANGER = 99L
    }

    @BeforeEach
    fun setUp() {
        challenges.put(challenge(ChallengeStatus.IN_PROGRESS))
        // accept() 가 만들어 두는 PENDING row 2건. 기권은 이 상태에서만 가능하다.
        seedVerification(CHALLENGER, VerificationStatus.PENDING)
        seedVerification(OPPONENT, VerificationStatus.PENDING)
    }

    private fun challenge(status: ChallengeStatus, result: ChallengeResult? = null) = Challenge(
        id = CHALLENGE_ID,
        challengerId = CHALLENGER,
        opponentId = OPPONENT,
        challengerMission = "운동 1시간",
        opponentMission = "책 30쪽",
        betContent = "커피 사기",
        challengeDate = LocalDate.of(2026, 8, 24),
        deadline = LocalDateTime.of(2026, 8, 25, 0, 0),
        status = status,
        result = result,
        createdAt = LocalDateTime.of(2026, 8, 24, 9, 0),
        completedAt = if (status == ChallengeStatus.COMPLETED) now else null,
        forfeitedBy = null,
    )

    private fun seedVerification(userId: Long, status: VerificationStatus) {
        verifications.save(
            Verification(
                id = null,
                challengeId = CHALLENGE_ID,
                userId = userId,
                status = status,
                photoUrl = if (status == VerificationStatus.VERIFIED) "$CHALLENGE_ID/$userId/photo.jpg" else null,
                verifiedAt = if (status == VerificationStatus.VERIFIED) now.minusHours(3) else null,
                createdAt = LocalDateTime.of(2026, 8, 24, 9, 5),
            ),
        )
    }

    /** 인증 상태를 갈아끼운다 (seed 로 이미 PENDING row 가 있으므로 교체다). */
    private fun setVerification(userId: Long, status: VerificationStatus) {
        val existing = verifications.all().single { it.userId == userId }
        verifications.save(
            existing.copy(
                status = status,
                photoUrl = if (status == VerificationStatus.VERIFIED) "$CHALLENGE_ID/$userId/photo.jpg" else null,
                verifiedAt = if (status == VerificationStatus.VERIFIED) now.minusHours(3) else null,
            ),
        )
    }

    private fun stored(): Challenge = challenges.findById(CHALLENGE_ID)!!

    // ─── 1. 성공 경로 (spec §3) ──────────────────────────────────

    @Test
    fun `기권하면 즉시 COMPLETED 가 되고 상대 승으로 확정된다`() {
        // 상대는 인증을 마친 상태 — "정상적으로 진 것" 에 가장 가까운 조합이다.
        setVerification(OPPONENT, VerificationStatus.VERIFIED)

        val view = service.forfeit(me = CHALLENGER, challengeId = CHALLENGE_ID)

        assertEquals(CHALLENGE_ID, view.challengeId)
        assertEquals(ChallengeStatus.COMPLETED, view.status, "기권은 자정을 기다리지 않는다")
        assertEquals(ChallengeResult.OPPONENT_WIN, view.result, "역할 기준 — 기권자의 반대편이 이긴다")
        assertEquals(Outcome.LOSE, view.myResult, "요청자 시점")
        assertEquals(now, view.completedAt, "completedAt 은 판정 시각(주입된 Clock)이자 기권 시각이다")

        val challenge = stored()
        assertEquals(ChallengeStatus.COMPLETED, challenge.status)
        assertEquals(ChallengeResult.OPPONENT_WIN, challenge.result)
        assertEquals(CHALLENGER, challenge.forfeitedBy, "기권자가 기록된다")
        assertEquals(now, challenge.completedAt)
    }

    /**
     * 🔴 **이 feature 의 핵심 단언이다** (spec §3).
     *
     * 상대도 미인증인 조합. 기존 판정 규칙만 있었다면 [ChallengeResult.BOTH_LOSE] 가 나오고
     * **양쪽 다 패배가 쌓인다.** 기권은 그 자리를 상대 승으로 바꾼다 — 사용자 결정(2026-09-05).
     *
     * [com.lwg.challenge.domain.challenge.ChallengeVerdictTest] 가 순수 함수 층에서 같은 것을
     * 고정하지만, 여기서 한 번 더 보는 이유는 **서비스가 `forfeited_by` 를 실제로 기록하고
     * `judge()` 가 그걸 읽어 환산하는 배관까지** 이어져야 이 결과가 나오기 때문이다.
     * 배관이 끊기면 순수 함수 테스트는 초록인 채 여기만 깨진다.
     */
    @Test
    fun `기권했는데 상대도 미인증이어도 상대가 이기고 상대 전적에 승리가 쌓인다`() {
        // 양쪽 다 PENDING (setUp 상태 그대로) — 아무도 인증하지 않았다.

        val view = service.forfeit(me = CHALLENGER, challengeId = CHALLENGE_ID)

        assertEquals(
            ChallengeResult.OPPONENT_WIN,
            view.result,
            "기존 규칙이면 BOTH_LOSE 가 나올 자리다. 여기가 깨지면 기권이 '미인증 취급' 으로 되돌아간 것이다",
        )

        val winner = userRecords.findByUserId(OPPONENT)!!
        assertEquals(1, winner.wins, "상대는 인증도 안 했지만 이겼다")
        assertEquals(0, winner.losses)
        assertEquals(1, winner.currentStreak)
        assertEquals(0, winner.currentLossStreak)
    }

    /**
     * 🔴 전적이 기권 경로에서도 갱신된다 — **숫자로** (spec §3).
     *
     * `judge()` 를 그대로 부른 결과이지 이 서비스가 따로 센 것이 아니다. 여기가 통과한다는 것은
     * 기권이 자정 배치와 **같은 집계 코드**를 탔다는 뜻이다.
     */
    @Test
    fun `기권자는 패 상대는 승으로 양쪽 전적이 갱신된다`() {
        service.forfeit(me = OPPONENT, challengeId = CHALLENGE_ID)

        val loser = userRecords.findByUserId(OPPONENT)!!
        assertEquals(1, loser.totalChallenges)
        assertEquals(0, loser.wins)
        assertEquals(1, loser.losses)
        assertEquals(0, loser.draws)
        assertEquals(0, loser.currentStreak, "연승은 끊긴다")
        assertEquals(1, loser.currentLossStreak, "연패가 이어진다")

        val winner = userRecords.findByUserId(CHALLENGER)!!
        assertEquals(1, winner.totalChallenges)
        assertEquals(1, winner.wins)
        assertEquals(0, winner.losses)
        assertEquals(1, winner.currentStreak)
        assertEquals(0, winner.currentLossStreak, "연패는 끊긴다")
    }

    /**
     * 🔴 `friend_records` 도 **방향 있는 2행**으로 갱신된다 (spec §3).
     *
     * 이 단언의 값은 숫자 자체가 아니라 *"배치 경로와 같은 함수를 탔다"* 는 증거라는 데 있다 —
     * 기권 전용 집계 코드를 새로 쓰면 이걸 함께 구현하지 않는 한 여기서 깨진다.
     */
    @Test
    fun `친구 전적도 양방향으로 갱신된다`() {
        service.forfeit(me = CHALLENGER, challengeId = CHALLENGE_ID)

        val mine = friendRecords.findByUserIdAndFriendId(CHALLENGER, OPPONENT)!!
        assertEquals(0, mine.wins)
        assertEquals(1, mine.losses)
        assertEquals(0, mine.draws)

        val theirs = friendRecords.findByUserIdAndFriendId(OPPONENT, CHALLENGER)!!
        assertEquals(1, theirs.wins)
        assertEquals(0, theirs.losses)
        assertEquals(0, theirs.draws)
    }

    /** 인증을 아직 안 올린 쪽의 `PENDING` row 는 `judge()` 가 `FAILED` 로 전이시킨다. */
    @Test
    fun `미인증 row 는 FAILED 로 전이한다`() {
        setVerification(OPPONENT, VerificationStatus.VERIFIED)

        service.forfeit(me = CHALLENGER, challengeId = CHALLENGE_ID)

        assertEquals(
            VerificationStatus.FAILED,
            verifications.all().single { it.userId == CHALLENGER }.status,
            "기권자는 인증하지 않은 채 끝났다",
        )
        assertEquals(
            VerificationStatus.VERIFIED,
            verifications.all().single { it.userId == OPPONENT }.status,
            "이미 인증한 쪽은 건드리지 않는다",
        )
    }

    // ─── 2. 알림 (spec §3 / T-B4) ────────────────────────────────

    /**
     * 상대에게 `CHALLENGE_FORFEITED` 이벤트가 **정확히 1건**.
     *
     * ⚠️ `notifications` row 증가는 여기서 알 수 없다 — `NotificationDispatcher` 가
     * `AFTER_COMMIT` 으로 받는 별도 빈이다. 여기서 고정하는 것은 *"이벤트가 한 번, 이긴 쪽에게,
     * 올바른 타입과 참조로 나간다"* 까지다.
     */
    @Test
    fun `기권당한 쪽에게 알림 이벤트가 정확히 한 건 발행된다`() {
        service.forfeit(me = CHALLENGER, challengeId = CHALLENGE_ID)

        val published = events.challengeEvents()
        assertEquals(1, published.size, "실제 발행: $published")
        val event = published.single()
        assertEquals(OPPONENT, event.recipientId, "받는 사람은 기권당한 쪽(= 이긴 쪽)이다")
        assertEquals(CHALLENGER, event.actorId, "문구의 닉네임 자리는 기권한 사람이다")
        assertEquals(NotificationType.CHALLENGE_FORFEITED, event.type)
        assertEquals(CHALLENGE_ID, event.challengeId, "reference_id = challengeId")
    }

    /** 반대 방향도 같다 — 상대가 기권하면 챌린저가 받는다. */
    @Test
    fun `상대가 기권하면 챌린저가 알림을 받는다`() {
        service.forfeit(me = OPPONENT, challengeId = CHALLENGE_ID)

        val event = events.challengeEvents().single()
        assertEquals(CHALLENGER, event.recipientId)
        assertEquals(OPPONENT, event.actorId)
    }

    // ─── 3. 거부 4종 (계약 §1 에러 표) ───────────────────────────

    @Test
    fun `없는 챌린지는 705 로 거부된다`() {
        val e = assertThrows<BusinessException> { service.forfeit(me = CHALLENGER, challengeId = 404L) }

        assertEquals(ResponseCode.ONE_BUTTON_DIALOG_ERROR, e.code)
        assertEquals(ChallengeForfeitService.MSG_NOT_FOUND, e.message)
        assertNothingHappened()
    }

    /**
     * 🔴 당사자가 아니면 **권한 오류(700)** 다. 상태 오류가 아니다.
     *
     * 순서가 중요하다 — 상태를 먼저 보면 제3자에게 *"진행 중인 챌린지가 아니에요"* 같은 답이
     * 나가면서 **남의 챌린지 상태가 새어나간다.**
     */
    @Test
    fun `당사자가 아니면 700 으로 거부된다`() {
        val e = assertThrows<BusinessException> { service.forfeit(me = STRANGER, challengeId = CHALLENGE_ID) }

        assertEquals(ResponseCode.SNACKBAR_ERROR, e.code)
        assertEquals(ChallengeForfeitService.MSG_NOT_MINE, e.message)
        assertNothingHappened()
    }

    /** `IN_PROGRESS` 가 아닌 상태 전수. `PENDING`·`COMPLETED`·`EXPIRED`·`REJECTED` 각각 (spec §3). */
    @Test
    fun `IN_PROGRESS 가 아니면 705 로 거부된다`() {
        listOf(
            ChallengeStatus.PENDING,
            ChallengeStatus.ACCEPTED,
            ChallengeStatus.REJECTED,
            ChallengeStatus.CONTRACT_SIGNING,
            ChallengeStatus.COMPLETED,
            ChallengeStatus.EXPIRED,
        ).forEach { status ->
            challenges.put(challenge(status))

            val e = assertThrows<BusinessException>("status=$status") {
                service.forfeit(me = CHALLENGER, challengeId = CHALLENGE_ID)
            }
            assertEquals(ResponseCode.ONE_BUTTON_DIALOG_ERROR, e.code, "status=$status")
            assertEquals(ChallengeForfeitService.MSG_NOT_IN_PROGRESS, e.message, "status=$status")
            assertNull(stored().forfeitedBy, "status=$status — 기권자가 기록되면 안 된다")
        }
        assertTrue(userRecords.all().isEmpty(), "전적이 하나도 쌓이면 안 된다")
        assertTrue(events.challengeEvents().isEmpty(), "알림이 나가면 안 된다")
    }

    /**
     * 🔴 **이미 인증한 사람은 기권할 수 없다** (spec §3).
     *
     * 705(단일버튼 다이얼로그)인 것은 이 응답의 의미가 *"네 화면이 낡았다"* 이기 때문이다 —
     * 기권 진입점은 **내 인증이 없을 때만** 뜨므로, 이 오류에 도달했다는 것은 진입점이 사라졌어야
     * 할 화면에 남아 있었다는 뜻이다. 사용자가 고칠 수 있는 것이 아니다(인증은 무를 수 없다).
     *
     * ⚠️ 계약 초안은 700 이었다. 바꾼 근거는 계약 §4 협의 이력.
     */
    @Test
    fun `이미 인증했으면 705 로 거부되고 아무것도 바뀌지 않는다`() {
        setVerification(CHALLENGER, VerificationStatus.VERIFIED)

        val e = assertThrows<BusinessException> { service.forfeit(me = CHALLENGER, challengeId = CHALLENGE_ID) }

        assertEquals(ResponseCode.ONE_BUTTON_DIALOG_ERROR, e.code)
        assertEquals(ChallengeForfeitService.MSG_ALREADY_VERIFIED, e.message)
        assertNothingHappened()
    }

    /** `FAILED` 는 인증이 아니다 — 기권할 수 있다 ([ChallengeVerdict] 의 미인증 정의). */
    @Test
    fun `인증이 FAILED 면 기권할 수 있다`() {
        setVerification(CHALLENGER, VerificationStatus.FAILED)

        val view = service.forfeit(me = CHALLENGER, challengeId = CHALLENGE_ID)

        assertEquals(ChallengeResult.OPPONENT_WIN, view.result)
    }

    // ─── 4. 되돌릴 수 없다 · 두 번은 안 된다 (spec §3) ───────────

    /**
     * 🔴 **두 번째 기권은 거부되고 `forfeited_by` 가 덮어써지지 않는다.**
     *
     * ⚠️ 이건 **순차** 검증이다. 진짜 동시 요청은 이 fake 로 재현할 수 없다 (클래스 KDoc).
     * 다만 동시 요청도 결국 이 게이트에 걸리도록 만드는 것이 `findByIdForUpdate` 의 역할이라,
     * *"게이트가 실제로 두 번째를 막는다"* 는 이 단언이 그 설계의 절반을 고정한다.
     */
    @Test
    fun `양쪽이 차례로 기권하면 두 번째는 거부되고 첫 기권자가 그대로 남는다`() {
        service.forfeit(me = CHALLENGER, challengeId = CHALLENGE_ID)
        val afterFirst = stored()

        val e = assertThrows<BusinessException> { service.forfeit(me = OPPONENT, challengeId = CHALLENGE_ID) }

        assertEquals(ResponseCode.ONE_BUTTON_DIALOG_ERROR, e.code)
        assertEquals(ChallengeForfeitService.MSG_NOT_IN_PROGRESS, e.message)

        val afterSecond = stored()
        assertEquals(CHALLENGER, afterSecond.forfeitedBy, "두 번째 기권자가 덮어쓰면 안 된다")
        assertEquals(afterFirst.result, afterSecond.result, "결과도 그대로다")
        assertEquals(afterFirst.completedAt, afterSecond.completedAt)
        assertEquals(1, events.challengeEvents().size, "알림도 한 번뿐이다")

        // 전적도 그대로 1건씩이다 — 두 번 세어지지 않았다.
        assertEquals(1, userRecords.findByUserId(CHALLENGER)!!.totalChallenges)
        assertEquals(1, userRecords.findByUserId(OPPONENT)!!.totalChallenges)
    }

    /**
     * 🔴 **자정 배치를 다시 돌려도 전적이 두 번 오르지 않는다** (spec §3).
     *
     * 기권 건은 즉시 `COMPLETED` 라 `findDueByStatus(IN_PROGRESS)` 에 애초에 걸리지 않는다.
     * 여기서는 **실제 [ChallengeJudgementRunner] 를 돌려** 그 사실과 숫자 불변을 함께 본다 —
     * *"대상 쿼리에 안 들어옴"* 만 확인하면 쿼리 조건이 바뀌는 날 조용히 뚫린다.
     */
    @Test
    fun `기권 후 자정 배치를 돌려도 전적이 두 번 오르지 않는다`() {
        service.forfeit(me = CHALLENGER, challengeId = CHALLENGE_ID)

        val before = userRecords.all().associate { it.userId to it.totalChallenges }
        val resultBefore = stored().result

        // 마감이 지난 뒤의 배치 실행 — 기권이 없었다면 이 챌린지가 판정 대상이었을 시각이다.
        val afterDeadline = Clock.fixed(Instant.parse("2026-08-24T15:05:00Z"), KstTime.ZONE)
        val runner = ChallengeJudgementRunner(
            challenges,
            ChallengeJudgementService(challenges, verifications, userRecords, friendRecords, afterDeadline),
            afterDeadline,
        )

        val run = runner.run()

        assertEquals(0, run.judged, "COMPLETED 라 판정 대상이 아니다")
        assertEquals(0, run.failed)
        assertEquals(
            before,
            userRecords.all().associate { it.userId to it.totalChallenges },
            "전적 숫자가 그대로여야 한다 — total_challenges 는 소급 정정이 불가능하다",
        )
        assertEquals(resultBefore, stored().result, "결과도 재판정되지 않는다")
        assertEquals(CHALLENGER, stored().forfeitedBy)
    }

    // ─── helper ──────────────────────────────────────────────────

    /** 거부된 요청은 **아무 흔적도 남기지 않는다** — 저장·전적·알림 전부 0. */
    private fun assertNothingHappened() {
        assertNull(stored().forfeitedBy, "기권자가 기록되면 안 된다")
        assertEquals(ChallengeStatus.IN_PROGRESS, stored().status, "상태가 바뀌면 안 된다")
        assertTrue(userRecords.all().isEmpty(), "전적이 쌓이면 안 된다: ${userRecords.all()}")
        assertTrue(friendRecords.all().isEmpty(), "친구 전적이 쌓이면 안 된다")
        assertTrue(events.challengeEvents().isEmpty(), "알림이 나가면 안 된다")
    }
}

/** ⚠️ `AFTER_COMMIT` 의미를 흉내내지 않는다 — 발행 즉시 기록한다. */
private class RecordingForfeitEventPublisher : ApplicationEventPublisher {
    private val published = mutableListOf<Any>()

    override fun publishEvent(event: Any) {
        published += event
    }

    fun challengeEvents(): List<ChallengeNotificationEvent> = published.filterIsInstance<ChallengeNotificationEvent>()
}
