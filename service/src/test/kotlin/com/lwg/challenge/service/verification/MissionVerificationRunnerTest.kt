package com.lwg.challenge.service.verification

import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.photo.PhotoStorage
import com.lwg.challenge.domain.verification.MissionVerdict
import com.lwg.challenge.domain.verification.MissionVerifier
import com.lwg.challenge.domain.verification.VerdictOutcome
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationStatus
import com.lwg.challenge.service.challenge.FakeChallenges
import com.lwg.challenge.service.challenge.FakeVerifications
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [MissionVerificationRunner] 단위 테스트 (ai-verification T-B4, ADR-0013).
 *
 * 🔴 **여기서 고정하는 명제 4개** (spec §3 수용 기준):
 * 1. 반려는 세 조건이 전부 참일 때만 일어나고 그 외는 전부 통과다 (fail-open)
 * 2. `ai_checked_at` 이 있으면 **다시 호출하지 않는다** (멱등 — 호출 1건당 정확히 1회)
 * 3. 한 건이 터져도 나머지가 검증되고, 터진 건은 `VERIFIED` 를 유지한다
 * 4. 미제출·기권 건은 **호출 0회**
 *
 * ⚠️ **덮지 못하는 것**: Gemini 가 실제로 스키마대로 답하는가. 그건 실호출로만 확인되고
 * (`GeminiMissionVerifierTest` 가 파싱까지, 실호출은 리포트의 미해결 항목),
 * 여기서는 [FakeVerifier] 가 어떤 판정이든 돌려준다는 전제로 **그 뒤의 규칙**만 본다.
 */
class MissionVerificationRunnerTest {

    /** 2026-08-25 00:05 KST — 배치가 실제로 도는 시각. */
    private val clock = Clock.fixed(Instant.parse("2026-08-24T15:05:00Z"), KstTime.ZONE)
    private val now = LocalDateTime.now(clock)

    private val challenges = FakeChallenges()
    private val verifications = FakeVerifications()
    private val photos = StubPhotoStorage()
    private val verifier = FakeVerifier()

    private val runner = MissionVerificationRunner(
        challengeRepository = challenges,
        verificationRepository = verifications,
        photoStorage = photos,
        missionVerifier = verifier,
        recorder = MissionVerdictRecorder(verifications),
    )

    // ─── 1. 반려 (세 조건이 전부 참일 때만) ──────────────────────

    @Test
    fun `REJECT 이고 신뢰도가 임계 이상이면 FAILED 로 전이한다`() {
        seed(challengeId = 1L)
        verifier.willReturn(MissionVerdict(VerdictOutcome.REJECT, 0.95, "무관한 사진"))

        val result = runner.verifyDueChallenges(now)

        assertEquals(VerificationStatus.FAILED, statusOf(1L, CHALLENGER))
        assertEquals(1, result.rejected)
        assertEquals(1, result.verified)
    }

    @Test
    fun `임계 미만 REJECT 는 VERIFIED 를 유지한다`() {
        seed(challengeId = 1L)
        verifier.willReturn(MissionVerdict(VerdictOutcome.REJECT, 0.79, "애매"))

        val result = runner.verifyDueChallenges(now)

        assertEquals(VerificationStatus.VERIFIED, statusOf(1L, CHALLENGER))
        assertEquals(0, result.rejected)
    }

    @Test
    fun `PASS 는 VERIFIED 를 유지한다`() {
        seed(challengeId = 1L)
        verifier.willReturn(MissionVerdict(VerdictOutcome.PASS, 0.99, "헬스장"))

        runner.verifyDueChallenges(now)

        assertEquals(VerificationStatus.VERIFIED, statusOf(1L, CHALLENGER))
    }

    /** 🔴 판정 로그 4컬럼이 실제로 남아야 한다 — 없으면 rubric 튜닝의 근거가 0 이다 (V13 주석). */
    @Test
    fun `판정 결과 4필드가 전부 기록된다`() {
        seed(challengeId = 1L)
        verifier.willReturn(MissionVerdict(VerdictOutcome.REJECT, 0.91, "책상 위 커피잔"))

        runner.verifyDueChallenges(now)

        val saved = find(1L, CHALLENGER)
        assertEquals(VerdictOutcome.REJECT, saved.aiVerdict)
        assertEquals(0.91, saved.aiConfidence)
        assertEquals("책상 위 커피잔", saved.aiReason)
        assertEquals(now, saved.aiCheckedAt)
    }

    // ─── 2. fail-open ────────────────────────────────────────────

    @Test
    fun `호출이 실패하면 VERIFIED 를 유지한다 — fail-open`() {
        seed(challengeId = 1L)
        verifier.willReturn(MissionVerdict.error("timeout"))

        val result = runner.verifyDueChallenges(now)

        assertEquals(VerificationStatus.VERIFIED, statusOf(1L, CHALLENGER))
        assertEquals(1, result.failed, "fail-open 건수가 배치 요약에 숫자로 올라와야 한다 (ADR-0013 §결정 4)")
        assertEquals(0, result.rejected)
    }

    @Test
    fun `판정 불가능한 미션은 VERIFIED 를 유지하고 fail-open 으로 집계된다`() {
        seed(challengeId = 1L)
        verifier.willReturn(MissionVerdict.unverifiable("사진으로 판정 불가"))

        val result = runner.verifyDueChallenges(now)

        assertEquals(VerificationStatus.VERIFIED, statusOf(1L, CHALLENGER))
        assertEquals(1, result.failed)
    }

    /**
     * 🔴 **실패한 건에도 `ai_checked_at` 을 찍는다.** 안 찍으면 Gemini 가 죽어 있는 동안
     * 매 실행이 같은 건을 끝없이 다시 부른다.
     */
    @Test
    fun `실패한 건에도 ai_checked_at 이 남아 다시 호출하지 않는다`() {
        seed(challengeId = 1L)
        verifier.willReturn(MissionVerdict.error("timeout"))

        runner.verifyDueChallenges(now)
        assertNotNull(find(1L, CHALLENGER).aiCheckedAt)

        val callsAfterFirst = verifier.callCount
        runner.verifyDueChallenges(now)

        assertEquals(callsAfterFirst, verifier.callCount)
    }

    @Test
    fun `사진 파일을 읽지 못하면 VERIFIED 를 유지하고 AI 를 부르지 않는다`() {
        seed(challengeId = 1L)
        photos.clear()

        val result = runner.verifyDueChallenges(now)

        assertEquals(VerificationStatus.VERIFIED, statusOf(1L, CHALLENGER))
        assertEquals(0, verifier.callCount)
        assertEquals(1, result.failed)
    }

    // ─── 3. 멱등 · 호출 횟수 ─────────────────────────────────────

    @Test
    fun `ai_checked_at 이 있으면 다시 호출하지 않는다 — 배치 멱등`() {
        seed(challengeId = 1L)
        verifier.willReturn(MissionVerdict(VerdictOutcome.PASS, 0.9, "ok"))

        runner.verifyDueChallenges(now)
        val callsAfterFirst = verifier.callCount

        val second = runner.verifyDueChallenges(now)

        assertEquals(callsAfterFirst, verifier.callCount)
        assertEquals(1, second.skipped)
        assertEquals(0, second.verified)
    }

    /** 🔴 ADR-0013 §결정 3 — 제출된 인증 1건당 정확히 1회. 미제출 건은 **0회**. */
    @Test
    fun `미제출 건은 AI 를 부르지 않는다`() {
        seedChallenge(1L)
        // challenger 만 제출, opponent 는 PENDING
        verifications.save(verification(1L, CHALLENGER, VerificationStatus.VERIFIED, "key-1"))
        verifications.save(verification(1L, OPPONENT, VerificationStatus.PENDING, photoKey = null))
        photos.put("key-1")
        verifier.willReturn(MissionVerdict(VerdictOutcome.PASS, 0.9, "ok"))

        val result = runner.verifyDueChallenges(now)

        assertEquals(1, verifier.callCount, "제출된 1건만 불러야 한다")
        assertEquals(1, result.verified)
        assertNull(find(1L, OPPONENT).aiCheckedAt)
    }

    /**
     * 🔴 기권 건은 즉시 `COMPLETED` 라 `IN_PROGRESS` 대상 쿼리에서 **자연히 빠진다.**
     * 그래서 상대가 올린 사진은 판별을 받지 않는다 — **의도다** (challenge-forfeit spec §3.1).
     * ⚠️ 이 테스트를 "버그" 로 읽고 기권 건을 대상에 되돌려 넣지 마라.
     */
    @Test
    fun `기권으로 COMPLETED 된 챌린지는 AI 를 부르지 않는다`() {
        seed(challengeId = 1L, status = ChallengeStatus.COMPLETED)
        verifier.willReturn(MissionVerdict(VerdictOutcome.REJECT, 0.99, "무관"))

        val result = runner.verifyDueChallenges(now)

        assertEquals(0, verifier.callCount)
        assertEquals(VerificationStatus.VERIFIED, statusOf(1L, CHALLENGER))
        assertEquals(0, result.verified)
    }

    @Test
    fun `마감 전 챌린지는 대상이 아니다`() {
        seed(challengeId = 1L, deadline = now.plusDays(1))

        runner.verifyDueChallenges(now)

        assertEquals(0, verifier.callCount)
    }

    // ─── 4. 건별 격리 ────────────────────────────────────────────

    @Test
    fun `한 건이 터져도 나머지가 검증된다`() {
        seed(challengeId = 1L)
        seed(challengeId = 2L)
        seed(challengeId = 3L)
        verifier.willReturn(MissionVerdict(VerdictOutcome.PASS, 0.9, "ok"))
        verifier.throwOnMission(missionOf(2L))

        val result = runner.verifyDueChallenges(now)

        assertEquals(2, result.verified)
        assertEquals(1, result.failed)
        // 🔴 터진 건은 VERIFIED 를 유지한다 — 실패가 사용자에게 불리하게 작용하면 안 된다.
        assertEquals(VerificationStatus.VERIFIED, statusOf(2L, CHALLENGER))
    }

    @Test
    fun `양쪽 다 반려되면 둘 다 FAILED 가 된다 — BOTH_LOSE 의 입력`() {
        seedChallenge(1L)
        verifications.save(verification(1L, CHALLENGER, VerificationStatus.VERIFIED, "key-c"))
        verifications.save(verification(1L, OPPONENT, VerificationStatus.VERIFIED, "key-o"))
        photos.put("key-c")
        photos.put("key-o")
        verifier.willReturn(MissionVerdict(VerdictOutcome.REJECT, 0.99, "둘 다 무관"))

        val result = runner.verifyDueChallenges(now)

        assertEquals(VerificationStatus.FAILED, statusOf(1L, CHALLENGER))
        assertEquals(VerificationStatus.FAILED, statusOf(1L, OPPONENT))
        assertEquals(2, result.rejected, "인증 건수이지 챌린지 수가 아니다")
    }

    // ─── 시드 헬퍼 ───────────────────────────────────────────────

    private fun seed(
        challengeId: Long,
        status: ChallengeStatus = ChallengeStatus.IN_PROGRESS,
        deadline: LocalDateTime = now.minusMinutes(5),
    ) {
        seedChallenge(challengeId, status, deadline)
        val key = "key-$challengeId"
        verifications.save(verification(challengeId, CHALLENGER, VerificationStatus.VERIFIED, key))
        photos.put(key)
    }

    private fun seedChallenge(
        challengeId: Long,
        status: ChallengeStatus = ChallengeStatus.IN_PROGRESS,
        deadline: LocalDateTime = now.minusMinutes(5),
    ) {
        challenges.put(
            Challenge(
                id = challengeId,
                challengerId = CHALLENGER,
                opponentId = OPPONENT,
                challengerMission = missionOf(challengeId),
                opponentMission = "상대 미션 $challengeId",
                betContent = "내기",
                challengeDate = LocalDate.of(2026, 8, 24),
                deadline = deadline,
                status = status,
                result = null,
                createdAt = now.minusDays(1),
                completedAt = null,
                forfeitedBy = null,
            ),
        )
    }

    private fun missionOf(challengeId: Long) = "미션 $challengeId"

    private fun verification(
        challengeId: Long,
        userId: Long,
        status: VerificationStatus,
        photoKey: String?,
    ) = Verification(
        id = null,
        challengeId = challengeId,
        userId = userId,
        status = status,
        photoUrl = photoKey,
        verifiedAt = if (status == VerificationStatus.VERIFIED) now.minusHours(3) else null,
        createdAt = now.minusDays(1),
    )

    private fun find(challengeId: Long, userId: Long): Verification =
        verifications.all().first { it.challengeId == challengeId && it.userId == userId }

    private fun statusOf(challengeId: Long, userId: Long) = find(challengeId, userId).status

    private companion object {
        const val CHALLENGER = 10L
        const val OPPONENT = 20L
    }
}

/** 디스크 없이 key → 바이트만 흉내낸다. 저장 규약(부를 때마다 새 key)은 이 테스트의 관심사가 아니다. */
private class StubPhotoStorage : PhotoStorage {
    private val store = mutableMapOf<String, ByteArray>()

    fun put(key: String) {
        store[key] = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    }

    fun clear() = store.clear()

    override fun store(challengeId: Long, userId: Long, bytes: ByteArray): String =
        "key-$challengeId-$userId".also { store[it] = bytes }

    override fun read(key: String): ByteArray? = store[key]

    override fun delete(key: String) {
        store.remove(key)
    }
}

/**
 * 판정을 지정할 수 있는 [MissionVerifier].
 *
 * ⚠️ **예외를 던지는 경로도 흉내낸다** — 진짜 구현은 던지지 않기로 약속돼 있지만
 * ([MissionVerifier] KDoc), 그 약속이 깨져도 배치가 멈추지 않아야 한다. 그걸 확인하는 것이
 * *"한 건이 터져도 나머지가 검증된다"* 테스트의 목적이다.
 */
private class FakeVerifier : MissionVerifier {
    private var verdict: MissionVerdict = MissionVerdict(VerdictOutcome.PASS, 0.9, "기본")
    private val throwOn = mutableSetOf<String>()

    var callCount = 0
        private set

    fun willReturn(v: MissionVerdict) {
        verdict = v
    }

    fun throwOnMission(missionText: String) {
        throwOn += missionText
    }

    override fun verify(missionText: String, photoBytes: ByteArray): MissionVerdict {
        callCount++
        if (missionText in throwOn) error("의도적으로 터뜨린 AI 호출 (mission=$missionText)")
        return verdict
    }
}
