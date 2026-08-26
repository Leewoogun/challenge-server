package com.lwg.challenge.service.challenge

import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeResult
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.friendrecord.FriendRecord
import com.lwg.challenge.domain.userrecord.UserRecord
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [ChallengeJudgementService] 단위 테스트 (challenge-result T-B2·T-B3).
 *
 * 커버하는 계약 §3 수용 기준:
 * - §3.1 판정 규칙표 4행이 상태·result·completedAt 까지 정확히 반영된다
 * - §3.1 미인증 측 verification 이 `PENDING → FAILED` 로 전이한다 (**row 가 없으면 만들지 않는다**)
 * - §3.3 `user_stats` 가 양측 갱신되고 streak 이 연승 규칙을 따른다
 * - §3.4 `friend_records` 가 **방향 있는 2행**으로 갱신된다
 * - §3.2 멱등 — 이미 처리된 챌린지는 전적을 두 번 세지 않는다
 * - §3.1 `EXPIRED` 는 판정이 아니다 — result 도 전적도 건드리지 않는다
 *
 * ⚠️ **덮지 못하는 것**: 트랜잭션 경계. 이 테스트에는 트랜잭션이 없으므로 *"롤백되면 전적도
 * 되돌아간다"*(§3.3)는 여기서 알 수 없다. 그건 `@Transactional` 이 붙어 있다는 것과
 * [ChallengeJudgementRunner] 와 빈이 분리돼 있다는 구조가 보장한다.
 *
 * 🔴 **알림 회귀는 별도 테스트가 없다.** [ChallengeJudgementService] 생성자에
 * `ApplicationEventPublisher` 가 **없다는 것 자체**가 구조적 보장이라, 넣는 순간 컴파일 단위에서
 * 드러난다. 계약 §3.2 의 *"notifications row 증가 0"* 은 그 방식으로 지킨다.
 */
class ChallengeJudgementServiceTest {

    private val challenges = FakeChallenges()
    private val verifications = FakeVerifications()
    private val userRecords = FakeUserRecords()
    private val friendRecords = FakeFriendRecords()

    /** 마감(2026-08-25 00:00 KST) 5분 뒤 = 배치가 실제로 도는 시각. */
    private val clock = Clock.fixed(Instant.parse("2026-08-24T15:05:00Z"), KstTime.ZONE)
    private val now = LocalDateTime.now(clock)

    private val service = ChallengeJudgementService(
        challenges,
        verifications,
        userRecords,
        friendRecords,
        clock,
    )

    private companion object {
        const val CHALLENGE_ID = 7L
        const val CHALLENGER = 1L
        const val OPPONENT = 2L
    }

    @BeforeEach
    fun setUp() {
        challenges.put(challenge(ChallengeStatus.IN_PROGRESS))
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
        completedAt = null,
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

    private fun judged(): Challenge = challenges.findById(CHALLENGE_ID)!!
    private fun statusOf(userId: Long) = verifications.all().single { it.userId == userId }.status

    // ─── 판정 규칙표 (계약 §3.1) ─────────────────────────────────

    @Test
    fun `양측 인증이면 무승부로 완료되고 양쪽 전적에 무승부가 쌓인다`() {
        seedVerification(CHALLENGER, VerificationStatus.VERIFIED)
        seedVerification(OPPONENT, VerificationStatus.VERIFIED)

        assertTrue(service.judge(CHALLENGE_ID))

        val challenge = judged()
        assertEquals(ChallengeStatus.COMPLETED, challenge.status)
        assertEquals(ChallengeResult.DRAW, challenge.result)
        assertEquals(now, challenge.completedAt, "completedAt 은 판정 시각(주입된 Clock)이다")

        listOf(CHALLENGER, OPPONENT).forEach { userId ->
            val record = userRecords.findByUserId(userId)!!
            assertEquals(1, record.totalChallenges, "userId=$userId")
            assertEquals(1, record.draws, "userId=$userId")
            assertEquals(0, record.wins, "userId=$userId")
            assertEquals(0, record.currentStreak, "무승부는 연승을 잇지 않는다 (userId=$userId)")
        }
    }

    @Test
    fun `양측 인증이면 VERIFIED 상태를 건드리지 않는다`() {
        seedVerification(CHALLENGER, VerificationStatus.VERIFIED)
        seedVerification(OPPONENT, VerificationStatus.VERIFIED)

        service.judge(CHALLENGE_ID)

        assertEquals(VerificationStatus.VERIFIED, statusOf(CHALLENGER))
        assertEquals(VerificationStatus.VERIFIED, statusOf(OPPONENT))
    }

    /** 🔴 계약 §3.1 *"미인증 시 자동 패배"* — 상대의 PENDING 이 FAILED 로 전이한다. */
    @Test
    fun `챌린저만 인증하면 챌린저 승이고 상대 인증이 FAILED 로 전이한다`() {
        seedVerification(CHALLENGER, VerificationStatus.VERIFIED)
        seedVerification(OPPONENT, VerificationStatus.PENDING)

        assertTrue(service.judge(CHALLENGE_ID))

        assertEquals(ChallengeResult.CHALLENGER_WIN, judged().result)
        assertEquals(VerificationStatus.VERIFIED, statusOf(CHALLENGER), "인증한 쪽은 건드리지 않는다")
        assertEquals(VerificationStatus.FAILED, statusOf(OPPONENT))

        val winner = userRecords.findByUserId(CHALLENGER)!!
        assertEquals(1, winner.wins)
        assertEquals(1, winner.currentStreak)
        assertEquals(1, winner.maxStreak)

        assertEquals(0, winner.currentLossStreak, "승리는 연패를 끊는다")

        val loser = userRecords.findByUserId(OPPONENT)!!
        assertEquals(1, loser.losses)
        assertEquals(0, loser.currentStreak)
        assertEquals(1, loser.currentLossStreak)
    }

    @Test
    fun `상대만 인증하면 상대 승이다`() {
        seedVerification(CHALLENGER, VerificationStatus.PENDING)
        seedVerification(OPPONENT, VerificationStatus.VERIFIED)

        service.judge(CHALLENGE_ID)

        assertEquals(ChallengeResult.OPPONENT_WIN, judged().result)
        assertEquals(1, userRecords.findByUserId(OPPONENT)!!.wins)
        assertEquals(1, userRecords.findByUserId(CHALLENGER)!!.losses)
    }

    @Test
    fun `양측 미인증이면 양측 패이고 양쪽 인증이 FAILED 로 전이한다`() {
        seedVerification(CHALLENGER, VerificationStatus.PENDING)
        seedVerification(OPPONENT, VerificationStatus.PENDING)

        assertTrue(service.judge(CHALLENGE_ID))

        assertEquals(ChallengeResult.BOTH_LOSE, judged().result)
        assertEquals(VerificationStatus.FAILED, statusOf(CHALLENGER))
        assertEquals(VerificationStatus.FAILED, statusOf(OPPONENT))

        listOf(CHALLENGER, OPPONENT).forEach { userId ->
            val record = userRecords.findByUserId(userId)!!
            assertEquals(1, record.losses, "양측 패는 양쪽 다 패배다 (userId=$userId)")
            assertEquals(0, record.wins, "userId=$userId")
            assertEquals(0, record.currentStreak, "userId=$userId")
            // 🔴 Outcome.BOTH_LOSE 는 접히지 않고 그대로 넘어오므로, 집계 쪽에서 LOSE 만 세면
            //    losses 도 연패도 0 이 된다 — 양측 패가 통째로 사라지는 회귀다.
            assertEquals(1, record.currentLossStreak, "양측 패는 연패를 잇는다 (userId=$userId)")
            assertEquals(1, record.maxLossStreak, "userId=$userId")
        }
    }

    // ─── verification row 부재 (§3.1) ───────────────────────────

    /**
     * 🔴 row 부재 = 미인증. 그리고 **없는 row 를 만들지 않는다** — `VerificationService` 가
     * *"수락 없이 인증된 챌린지를 만들지 않는다"* 며 거부하는 것과 같은 이유로, 배치가 인증 row 를
     * 창조하면 데이터가 깨진 사실이 감춰진다.
     */
    @Test
    fun `verification row 가 아예 없으면 미인증으로 보고 row 를 만들지 않는다`() {
        assertTrue(service.judge(CHALLENGE_ID))

        assertEquals(ChallengeResult.BOTH_LOSE, judged().result)
        assertTrue(verifications.all().isEmpty(), "배치가 없던 인증 row 를 만들면 안 된다")
        assertEquals(1, userRecords.findByUserId(CHALLENGER)!!.losses)
        assertEquals(1, userRecords.findByUserId(OPPONENT)!!.losses)
    }

    @Test
    fun `한쪽만 row 가 있으면 없는 쪽만 미인증이다`() {
        seedVerification(CHALLENGER, VerificationStatus.VERIFIED)

        service.judge(CHALLENGE_ID)

        assertEquals(ChallengeResult.CHALLENGER_WIN, judged().result)
        assertEquals(1, verifications.all().size, "상대 row 를 새로 만들면 안 된다")
    }

    /** 이미 `FAILED` 인 row 는 다시 저장할 필요가 없다 — PENDING 만 전이 대상이다. */
    @Test
    fun `이미 FAILED 인 인증은 미인증으로 세되 다시 저장하지 않는다`() {
        seedVerification(CHALLENGER, VerificationStatus.VERIFIED)
        seedVerification(OPPONENT, VerificationStatus.FAILED)
        val savesBefore = verifications.saveCount

        service.judge(CHALLENGE_ID)

        assertEquals(ChallengeResult.CHALLENGER_WIN, judged().result)
        assertEquals(savesBefore, verifications.saveCount, "FAILED → FAILED 는 불필요한 UPDATE 다")
    }

    // ─── friend_records (계약 §3.4) ─────────────────────────────

    /** 🔴 챌린지 1건이 **방향 있는 2행**을 만든다. 한 행만 쓰면 반대 관점 조회가 뒤집힌다. */
    @Test
    fun `판정은 friend_records 를 양방향 2행으로 갱신한다`() {
        seedVerification(CHALLENGER, VerificationStatus.VERIFIED)
        seedVerification(OPPONENT, VerificationStatus.PENDING)

        service.judge(CHALLENGE_ID)

        assertEquals(2, friendRecords.all().size)

        val forward = friendRecords.findByUserIdAndFriendId(CHALLENGER, OPPONENT)!!
        assertEquals(1, forward.wins, "챌린저 → 상대 방향에는 챌린저의 승이 쌓인다")
        assertEquals(0, forward.losses)

        val backward = friendRecords.findByUserIdAndFriendId(OPPONENT, CHALLENGER)!!
        assertEquals(0, backward.wins)
        assertEquals(1, backward.losses, "상대 → 챌린저 방향에는 상대의 패가 쌓인다")
    }

    @Test
    fun `무승부는 friend_records 양쪽 draws 를 올린다`() {
        seedVerification(CHALLENGER, VerificationStatus.VERIFIED)
        seedVerification(OPPONENT, VerificationStatus.VERIFIED)

        service.judge(CHALLENGE_ID)

        assertEquals(1, friendRecords.findByUserIdAndFriendId(CHALLENGER, OPPONENT)!!.draws)
        assertEquals(1, friendRecords.findByUserIdAndFriendId(OPPONENT, CHALLENGER)!!.draws)
    }

    /**
     * 🔴 기존 행이 있으면 **그 위에 누적**해야 한다. 새 행을 INSERT 하면
     * `uq_friend_records_user_friend` 에 걸려 판정 트랜잭션 전체가 롤백된다.
     */
    @Test
    fun `이미 있는 friend_records 행 위에 누적한다`() {
        friendRecords.save(
            FriendRecord(
                id = 42L,
                userId = CHALLENGER,
                friendId = OPPONENT,
                wins = 3,
                losses = 1,
                draws = 0,
            ),
        )
        seedVerification(CHALLENGER, VerificationStatus.VERIFIED)

        service.judge(CHALLENGE_ID)

        val forward = friendRecords.findByUserIdAndFriendId(CHALLENGER, OPPONENT)!!
        assertEquals(4, forward.wins, "기존 3승 위에 쌓여야 한다")
        assertEquals(1, forward.losses)
        assertEquals(42L, forward.id, "id 를 잃으면 새 행 INSERT 가 되어 UNIQUE 제약에 걸린다")
    }

    /** `user_stats` 도 같은 규칙 — 기존 전적 위에 누적된다. */
    @Test
    fun `이미 있는 user_stats 위에 누적한다`() {
        userRecords.save(
            UserRecord(
                userId = CHALLENGER,
                totalChallenges = 10,
                wins = 6,
                losses = 3,
                draws = 1,
                currentStreak = 2,
                maxStreak = 4,
                currentLossStreak = 0,
                maxLossStreak = 2,
                updatedAt = now.minusDays(1),
            ),
        )
        seedVerification(CHALLENGER, VerificationStatus.VERIFIED)

        service.judge(CHALLENGE_ID)

        val record = userRecords.findByUserId(CHALLENGER)!!
        assertEquals(11, record.totalChallenges)
        assertEquals(7, record.wins)
        assertEquals(3, record.currentStreak)
        assertEquals(4, record.maxStreak, "3연승은 기존 최고 4연승을 넘지 못한다")
        assertEquals(0, record.currentLossStreak)
        assertEquals(2, record.maxLossStreak, "승리가 과거 최대 연패 기록을 지우면 안 된다")
        assertEquals(now, record.updatedAt)
    }

    // ─── 🔴 멱등 (계약 §3.2) ────────────────────────────────────

    /**
     * 🔴 목록을 뽑은 뒤 판정 사이에 상태가 바뀌었어도 **전적을 두 번 세지 않는다.**
     * `total_challenges` 는 되돌릴 방법이 없어서 이 가드가 유일한 방어선이다.
     */
    @Test
    fun `이미 COMPLETED 인 챌린지는 판정하지 않고 아무것도 저장하지 않는다`() {
        challenges.put(challenge(ChallengeStatus.COMPLETED, ChallengeResult.DRAW))
        seedVerification(CHALLENGER, VerificationStatus.VERIFIED)
        seedVerification(OPPONENT, VerificationStatus.VERIFIED)
        challenges.resetSaveCount()

        assertFalse(service.judge(CHALLENGE_ID))

        assertEquals(0, challenges.saveCount, "저장 호출이 0 건이어야 한다")
        assertTrue(userRecords.all().isEmpty(), "전적이 한 줄도 생기면 안 된다")
        assertTrue(friendRecords.all().isEmpty())
        assertEquals(ChallengeResult.DRAW, judged().result, "기존 result 가 덮이면 안 된다")
    }

    @Test
    fun `IN_PROGRESS 가 아닌 다른 상태들도 판정 대상이 아니다`() {
        listOf(
            ChallengeStatus.PENDING,
            ChallengeStatus.ACCEPTED,
            ChallengeStatus.REJECTED,
            ChallengeStatus.CONTRACT_SIGNING,
            ChallengeStatus.EXPIRED,
        ).forEach { status ->
            challenges.put(challenge(status))
            assertFalse(service.judge(CHALLENGE_ID), "status=$status")
        }
        assertTrue(userRecords.all().isEmpty())
    }

    @Test
    fun `없는 챌린지는 false 를 돌려준다`() {
        assertFalse(service.judge(12345L))
        assertTrue(userRecords.all().isEmpty())
    }

    // ─── expire — 🔴 판정이 아니다 (계약 §3.1) ──────────────────

    /**
     * 🔴 계약서가 체결된 적이 없으므로 승부가 성립하지 않았다. `result` 는 null 로 남고
     * **전적도 `friend_records` 도 갱신하지 않는다** — 여기서 집계하면 도전장을 받고 무시당한
     * 사람의 패배 수가 올라간다.
     */
    @Test
    fun `PENDING 만료는 EXPIRED 로만 바꾸고 result 와 전적을 건드리지 않는다`() {
        challenges.put(challenge(ChallengeStatus.PENDING))

        assertTrue(service.expire(CHALLENGE_ID))

        val expired = judged()
        assertEquals(ChallengeStatus.EXPIRED, expired.status)
        assertNull(expired.result, "EXPIRED 는 판정이 아니다 — result 는 null 로 남는다")
        assertNull(expired.completedAt, "completedAt 도 건드리지 않는다")
        assertTrue(userRecords.all().isEmpty(), "user_stats 저장 호출이 0 건이어야 한다")
        assertTrue(friendRecords.all().isEmpty(), "friend_records 저장 호출이 0 건이어야 한다")
    }

    @Test
    fun `이미 EXPIRED 면 만료시키지 않는다`() {
        challenges.put(challenge(ChallengeStatus.EXPIRED))
        challenges.resetSaveCount()

        assertFalse(service.expire(CHALLENGE_ID))

        assertEquals(0, challenges.saveCount)
    }

    /** 수락된 뒤 마감을 넘긴 챌린지는 만료가 아니라 **판정** 대상이다. */
    @Test
    fun `IN_PROGRESS 는 만료 대상이 아니다`() {
        assertFalse(service.expire(CHALLENGE_ID))

        assertEquals(ChallengeStatus.IN_PROGRESS, judged().status)
    }

    @Test
    fun `없는 챌린지 만료는 false 다`() {
        assertFalse(service.expire(12345L))
    }
}
