package com.lwg.challenge.service.challenge

import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeResult
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [ChallengeJudgementRunner] 단위 테스트 (challenge-result T-B1, 계약 §3.2).
 *
 * 🔴 **진짜 [ChallengeJudgementService] 를 쓴다.** mock 을 끼우면 *"판정이 호출됐다"* 까지만
 * 알 수 있는데, 여기서 확인하고 싶은 것은 *"밀린 챌린지가 실제로 COMPLETED 가 됐는가"* 다.
 * 실패 주입도 서비스가 아니라 [FakeChallenges.failOnFindById] 로 저장소를 터뜨려서 한다 —
 * 실제 실패 경로(DB 오류)에 더 가깝다.
 *
 * 커버하는 계약 §3.2 수용 기준:
 * - **소급 보장** — 대상 선정 기준이 실행 시각이 아니라 `deadline` 이라 며칠 밀려도 전부 잡힌다
 * - **트랜잭션 분리** — 한 건이 터져도 나머지는 판정된다
 * - 판정(`IN_PROGRESS`)과 만료(`PENDING`)가 한 번의 실행에서 모두 일어난다
 *
 * ⚠️ **덮지 못하는 것**: `@Scheduled` 가 실제로 00:05 KST 에 도는가. cron 파싱과 zone 적용은
 * Spring 소관이고 단위 테스트로 확인할 수 없다 — `ChallengeJudgementScheduler` 의 KDoc 이
 * 그 위험(zone 미명시 시 조용히 09:05 에 돔)을 명시하는 것으로 대신한다.
 */
class ChallengeJudgementRunnerTest {

    private val challenges = FakeChallenges()
    private val verifications = FakeVerifications()
    private val userRecords = FakeUserRecords()
    private val friendRecords = FakeFriendRecords()

    /** 2026-08-25 00:05 KST — 배치가 실제로 도는 시각. */
    private val clock = Clock.fixed(Instant.parse("2026-08-24T15:05:00Z"), KstTime.ZONE)
    private val now = LocalDateTime.now(clock)

    private val judgementService = ChallengeJudgementService(
        challenges,
        verifications,
        userRecords,
        friendRecords,
        clock,
    )

    private val runner = ChallengeJudgementRunner(challenges, judgementService, clock)

    private fun seedChallenge(
        id: Long,
        status: ChallengeStatus,
        deadline: LocalDateTime,
        challengerId: Long = id * 10,
        opponentId: Long = id * 10 + 1,
    ): Challenge {
        val challenge = Challenge(
            id = id,
            challengerId = challengerId,
            opponentId = opponentId,
            challengerMission = "운동 1시간",
            opponentMission = if (status == ChallengeStatus.PENDING) null else "책 30쪽",
            betContent = "커피 사기",
            challengeDate = deadline.toLocalDate().minusDays(1),
            deadline = deadline,
            status = status,
            result = null,
            createdAt = deadline.minusDays(1),
            completedAt = null,
        )
        challenges.put(challenge)
        return challenge
    }

    private fun seedVerified(challengeId: Long, userId: Long) {
        verifications.save(
            Verification(
                id = null,
                challengeId = challengeId,
                userId = userId,
                status = VerificationStatus.VERIFIED,
                photoUrl = "$challengeId/$userId/photo.jpg",
                verifiedAt = now.minusHours(3),
                createdAt = now.minusDays(1),
            ),
        )
    }

    private fun statusOf(id: Long) = challenges.all().single { it.id == id }.status

    // ─── 🔴 소급 판정 (계약 §3.2) ───────────────────────────────

    /**
     * 🔴 **이 feature 의 핵심 보장이다.** 서버가 사흘 죽어 있었어도 다음 실행 한 번이 밀린 분을
     * 전부 집는다. 기준이 "지난 자정" 이 아니라 `deadline <= now` 이기 때문에 성립한다 —
     * 여기가 깨지면 죽어 있던 기간의 챌린지가 영원히 IN_PROGRESS 로 남는다.
     */
    @Test
    fun `마감이 사흘 지난 챌린지도 판정 대상에 들어온다`() {
        seedChallenge(1L, ChallengeStatus.IN_PROGRESS, now.minusDays(3))
        seedChallenge(2L, ChallengeStatus.IN_PROGRESS, now.minusDays(1))
        seedChallenge(3L, ChallengeStatus.IN_PROGRESS, now.minusMinutes(5))

        val result = runner.run()

        assertEquals(3, result.judged, "밀린 분이 전부 잡혀야 한다")
        listOf(1L, 2L, 3L).forEach { assertEquals(ChallengeStatus.COMPLETED, statusOf(it), "challengeId=$it") }
    }

    /** 아직 마감 전인 챌린지는 건드리지 않는다 — 진행 중인 대결을 조기 판정하면 안 된다. */
    @Test
    fun `마감이 아직 안 지난 챌린지는 대상이 아니다`() {
        seedChallenge(1L, ChallengeStatus.IN_PROGRESS, now.plusHours(1))
        seedChallenge(2L, ChallengeStatus.IN_PROGRESS, now.plusDays(1))

        val result = runner.run()

        assertEquals(0, result.judged)
        assertEquals(ChallengeStatus.IN_PROGRESS, statusOf(1L))
        assertEquals(ChallengeStatus.IN_PROGRESS, statusOf(2L))
    }

    /**
     * 🔴 경계는 `<=` 다 — 마감 **정각을 포함**한다. `findReceivedPending` 의 `deadline > now`
     * (정각이면 목록에서 사라진다)와 상보적이라, 어느 쪽에도 속하지 않는 틈이 없다.
     */
    @Test
    fun `마감 정각인 챌린지는 판정 대상에 포함된다`() {
        seedChallenge(1L, ChallengeStatus.IN_PROGRESS, now)

        assertEquals(1, runner.run().judged)
        assertEquals(ChallengeStatus.COMPLETED, statusOf(1L))
    }

    // ─── 🔴 건별 트랜잭션 — 한 건이 터져도 나머지는 간다 ────────

    /**
     * 🔴 계약 §3.2 *"챌린지 1건 = 1 트랜잭션. 한 건이 터져도 나머지는 판정된다."*
     *
     * Runner 에 `@Transactional` 이 붙으면 이 테스트는 통과하지만 운영에서는 그날 판정한 전부가
     * 롤백된다 — 단위 테스트가 잡지 못하는 지점이라 [ChallengeJudgementRunner] KDoc 이 함께 막는다.
     */
    @Test
    fun `한 건이 예외를 던져도 나머지는 판정되고 failed 로 센다`() {
        seedChallenge(1L, ChallengeStatus.IN_PROGRESS, now.minusDays(1))
        seedChallenge(2L, ChallengeStatus.IN_PROGRESS, now.minusDays(1))
        seedChallenge(3L, ChallengeStatus.IN_PROGRESS, now.minusDays(1))
        challenges.failOnFindById += 2L

        val result = runner.run()

        assertEquals(2, result.judged)
        assertEquals(1, result.failed)
        assertEquals(0, result.skipped)
        assertEquals(ChallengeStatus.COMPLETED, statusOf(1L))
        assertEquals(ChallengeStatus.COMPLETED, statusOf(3L))
        assertEquals(
            ChallengeStatus.IN_PROGRESS,
            statusOf(2L),
            "실패한 건은 status 가 그대로여야 다음 실행이 재시도한다",
        )
    }

    /** 실패해도 예외가 밖으로 나가지 않는다 — 나가면 스케줄러의 다음 실행이 취소된다. */
    @Test
    fun `전부 실패해도 예외를 던지지 않는다`() {
        seedChallenge(1L, ChallengeStatus.IN_PROGRESS, now.minusDays(1))
        challenges.failOnFindById += 1L

        val result = runner.run()

        assertEquals(0, result.judged)
        assertEquals(1, result.failed)
    }

    // ─── 판정 + 만료가 한 실행에서 모두 일어난다 ─────────────────

    @Test
    fun `IN_PROGRESS 판정과 PENDING 만료가 한 번의 실행에서 모두 일어난다`() {
        seedChallenge(1L, ChallengeStatus.IN_PROGRESS, now.minusDays(1))
        seedChallenge(2L, ChallengeStatus.IN_PROGRESS, now.minusDays(2))
        seedChallenge(3L, ChallengeStatus.PENDING, now.minusDays(1))

        val result = runner.run()

        assertEquals(2, result.judged)
        assertEquals(1, result.expired)
        assertEquals(0, result.failed)
        assertEquals(ChallengeStatus.COMPLETED, statusOf(1L))
        assertEquals(ChallengeStatus.COMPLETED, statusOf(2L))
        assertEquals(ChallengeStatus.EXPIRED, statusOf(3L))
    }

    /**
     * 🔴 만료는 판정이 아니다 — `EXPIRED` 는 전적에 들어가지 않는다 (계약 §3.1).
     * 판정된 챌린지의 전적만 쌓여야 한다.
     */
    @Test
    fun `만료된 챌린지는 전적에 집계되지 않는다`() {
        seedChallenge(1L, ChallengeStatus.IN_PROGRESS, now.minusDays(1), challengerId = 10L, opponentId = 11L)
        seedVerified(1L, 10L)
        seedChallenge(2L, ChallengeStatus.PENDING, now.minusDays(1), challengerId = 20L, opponentId = 21L)

        runner.run()

        // 판정된 1번의 양측만 전적이 생긴다.
        assertEquals(setOf(10L, 11L), userRecords.all().map { it.userId }.toSet())
        assertEquals(1, userRecords.findByUserId(10L)!!.wins)
        assertEquals(1, userRecords.findByUserId(11L)!!.losses)
        assertNull(challenges.all().single { it.id == 2L }.result, "EXPIRED 는 result 가 null 이다")
        // friend_records 도 판정된 건에서만 2행.
        assertEquals(2, friendRecords.all().size)
    }

    @Test
    fun `판정 결과가 result 컬럼까지 기록된다`() {
        seedChallenge(1L, ChallengeStatus.IN_PROGRESS, now.minusDays(1), challengerId = 10L, opponentId = 11L)
        seedVerified(1L, 11L)

        runner.run()

        assertEquals(ChallengeResult.OPPONENT_WIN, challenges.all().single { it.id == 1L }.result)
    }

    // ─── 대상이 없을 때 / 멱등 ──────────────────────────────────

    @Test
    fun `대상이 없으면 전부 0 인 결과를 돌려준다`() {
        val result = runner.run()

        assertEquals(JudgementRunResult(0, 0, 0, 0), result)
        assertTrue(result.isEmpty(), "isEmpty 가 true 여야 자정마다 빈 로그가 쌓이지 않는다")
    }

    /** 이미 처리된 상태는 대상 쿼리에서 빠진다 — 두 번 돌려도 전적이 두 배가 되지 않는다. */
    @Test
    fun `두 번 실행해도 전적이 두 번 세어지지 않는다`() {
        seedChallenge(1L, ChallengeStatus.IN_PROGRESS, now.minusDays(1), challengerId = 10L, opponentId = 11L)
        seedVerified(1L, 10L)

        val first = runner.run()
        val second = runner.run()

        assertEquals(1, first.judged)
        assertEquals(JudgementRunResult(0, 0, 0, 0), second, "이미 COMPLETED 라 대상 집합에서 빠진다")
        assertEquals(1, userRecords.findByUserId(10L)!!.totalChallenges)
        assertEquals(1, userRecords.findByUserId(11L)!!.totalChallenges)
    }

    /** `COMPLETED` / `REJECTED` 등은 마감이 지났어도 대상이 아니다. */
    @Test
    fun `판정 대상 상태가 아닌 챌린지는 마감이 지나도 건드리지 않는다`() {
        seedChallenge(1L, ChallengeStatus.COMPLETED, now.minusDays(1))
        seedChallenge(2L, ChallengeStatus.REJECTED, now.minusDays(1))
        seedChallenge(3L, ChallengeStatus.EXPIRED, now.minusDays(1))
        seedChallenge(4L, ChallengeStatus.CONTRACT_SIGNING, now.minusDays(1))

        val result = runner.run()

        assertEquals(JudgementRunResult(0, 0, 0, 0), result)
    }

    // ─── 대상 선정에 넘어가는 시각 (소급의 근거) ────────────────

    /**
     * 🔴 대상 선정에 넘어가는 `now` 는 **주입된 [Clock] 의 값**이어야 한다.
     *
     * `LocalDateTime.now()` 를 인자 없이 부르면 JVM 기본 타임존을 따라 AWS(UTC)에서 9시간
     * 어긋나고(ADR-0010), 그러면 자정 직후 실행이 **전날 15:05 기준으로 대상을 뽑는다** —
     * 그날 마감된 챌린지가 통째로 누락된다. 값이 그냥 다를 뿐이라 다른 테스트는 전부 초록이다.
     *
     * `now` 와 `now + 1초` 사이에만 마감이 있는 챌린지를 두어, 정확히 `Clock` 값을 썼을 때만
     * 대상에 들어오게 만든다.
     */
    @Test
    fun `대상 선정 기준 시각은 주입된 Clock 을 따른다`() {
        seedChallenge(1L, ChallengeStatus.IN_PROGRESS, now)
        seedChallenge(2L, ChallengeStatus.IN_PROGRESS, now.plusSeconds(1))

        val result = runner.run()

        assertEquals(1, result.judged, "Clock 시각(=$now)을 기준으로 정확히 한 건만 잡혀야 한다")
        assertEquals(ChallengeStatus.COMPLETED, statusOf(1L))
        assertEquals(ChallengeStatus.IN_PROGRESS, statusOf(2L))
    }

    @Test
    fun `판정 시각은 Clock 값이 completedAt 에 기록된다`() {
        seedChallenge(1L, ChallengeStatus.IN_PROGRESS, now.minusDays(3))

        runner.run()

        assertEquals(now, challenges.all().single { it.id == 1L }.completedAt)
    }
}
