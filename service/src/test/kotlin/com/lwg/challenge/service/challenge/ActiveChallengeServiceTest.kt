package com.lwg.challenge.service.challenge

import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeResult
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.Outcome
import com.lwg.challenge.domain.challenge.ReceivedChallenge
import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationRepository
import com.lwg.challenge.domain.verification.VerificationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * `ActiveChallengeService` 단위 테스트 (challenge-result T-B4 / 계약 §2).
 *
 * ## 무엇을 덮는가 — 이 서비스가 새로 떠안은 책임 셋
 *
 * 1. **이어붙이는 순서**: 진행 중(행동 대상) 먼저, 판정 결과(확인 대상) 나중.
 * 2. **`since` 계산**: `now - completedRetentionDays`. 이 값 하나가 *"언제부터 결과를 영구히
 *    볼 수 없게 되는가"* 를 정한다 (계약 §2.3) — `Clock.fixed` 로 고정해 결정적으로 본다.
 * 3. **역할 → 내 시점 환산**: 같은 `result` 가 조회자에 따라 `WIN` 이 되기도 `LOSE` 가 되기도
 *    한다. 뒤집기 자체는 `ChallengeVerdict` 의 것이고 여기서 보는 것은 **인자를 옳게 넘겼는가**다
 *    (`isChallenger` 를 반대로 넘기는 실수는 컴파일도 통과하고 타입도 맞는다).
 *
 * ⚠️ **저장소를 흉내낸 것은 정렬·필터까지다.** 실제 JPQL 의 경계(`completed_at > :since`)가
 * 맞는지는 여기서 알 수 없다 — fake 가 같은 규칙을 다시 구현할 뿐이다. 그래서 [since 는] 인자로
 * 넘어간 값 자체를 붙잡아 단언한다(`lastSince`). 서비스가 계산해서 넘기는 값은 **서비스의 책임**이고,
 * 그 값으로 무엇을 거르는지는 저장소의 책임이라 층을 나눠 본다.
 *
 * Spring 컨텍스트 없이 in-memory fake 로 돌린다 (`ChallengeCommandServiceTest` 와 같은 방식).
 * fake 를 **중첩 클래스**로 둔 이유: 같은 패키지에 `FakeChallenges`/`FakeChallengeRepository` 가
 * 이미 있어 top-level 로 두면 JVM 클래스명이 충돌한다 (`JudgementFakes` KDoc 참조).
 */
class ActiveChallengeServiceTest {

    // KST 2026-08-25 10:00. 벽시계 = 저장값 (ADR-0010 이후 clock 자체가 KST).
    private val now: LocalDateTime = LocalDateTime.of(2026, 8, 25, 10, 0, 0)
    private val clock: Clock = Clock.fixed(now.atZone(KstTime.ZONE).toInstant(), KstTime.ZONE)

    private val me = 1L
    private val other = 2L

    private val challenges = FakeChallengeStore()
    private val users = FakeUsers()
    private val verifications = FakeVerificationStore()

    private val retentionDays = 7L

    private val service = ActiveChallengeService(
        userRepository = users,
        challengeRepository = challenges,
        verificationRepository = verifications,
        clock = clock,
        completedRetentionDays = retentionDays,
    )

    init {
        users.put(user(me, "나"))
        users.put(user(other, "민수"))
    }

    // ─── 1. 순서 — 진행 중 먼저, 결과 나중 ────────────────────────

    @Test
    fun `진행 중이 먼저 나오고 판정된 것이 뒤에 붙는다`() {
        // 🔴 deadline 만 보면 판정된 쪽(8/20)이 더 이르다. 그런데도 뒤에 와야 한다 —
        //    두 목록은 **정렬 기준이 서로 다르고**, 이어붙이는 순서가 곧 응답 순서다 (계약 §2.3).
        challenges.put(inProgress(id = 10L, challengerId = me, deadline = now.plusDays(1)))
        challenges.put(
            completed(
                id = 20L,
                challengerId = me,
                deadline = now.minusDays(5),
                completedAt = now.minusDays(5),
                result = ChallengeResult.CHALLENGER_WIN,
            ),
        )

        val views = service.getActiveChallenges(me)

        assertEquals(listOf(10L, 20L), views.map { it.challengeId })
        assertEquals(ChallengeStatus.IN_PROGRESS, views[0].status)
        assertEquals(ChallengeStatus.COMPLETED, views[1].status)
    }

    @Test
    fun `진행 중도 결과도 없으면 빈 목록이다`() {
        assertEquals(emptyList<ActiveChallengeView>(), service.getActiveChallenges(me))
    }

    // ─── 2. since = now - retentionDays ──────────────────────────

    @Test
    fun `보관 기간만큼 거슬러 올라간 시각을 저장소에 넘긴다`() {
        challenges.put(inProgress(id = 10L, challengerId = me, deadline = now.plusDays(1)))

        service.getActiveChallenges(me)

        // 🔴 프로퍼티가 며칠이든 **now 기준**으로 계산돼야 한다. `LocalDateTime.now()` 를 직접
        //    부르면(= Clock 미주입) 이 단언이 흔들리고, 그게 이 테스트가 잡으려는 회귀다.
        assertEquals(now.minusDays(retentionDays), challenges.lastSince)
    }

    @Test
    fun `보관 기간이 바뀌면 넘기는 시각도 함께 바뀐다`() {
        val shortLived = ActiveChallengeService(
            userRepository = users,
            challengeRepository = challenges,
            verificationRepository = verifications,
            clock = clock,
            completedRetentionDays = 1L,
        )

        shortLived.getActiveChallenges(me)

        assertEquals(now.minusDays(1), challenges.lastSince)
    }

    // ─── 3. myResult — 역할 기준 → 내 시점 ────────────────────────

    @Test
    fun `내가 challenger 면 CHALLENGER_WIN 이 WIN 이다`() {
        challenges.put(
            completed(
                id = 30L,
                challengerId = me,
                deadline = now.minusDays(1),
                completedAt = now.minusDays(1),
                result = ChallengeResult.CHALLENGER_WIN,
            ),
        )

        val view = service.getActiveChallenges(me).single()

        // 역할 기준 원본은 그대로 살아 있고(앱이 "양쪽 다 패배" 같은 걸 그릴 근거),
        // myResult 만 내 시점으로 뒤집힌다.
        assertEquals(ChallengeResult.CHALLENGER_WIN, view.result)
        assertEquals(Outcome.WIN, view.myResult)
    }

    @Test
    fun `내가 opponent 면 같은 CHALLENGER_WIN 이 LOSE 다`() {
        // 🔴 위 케이스와 **row 가 동일**하다. 조회자만 다르다 — 같은 데이터가 시점에 따라
        //    반대로 읽힌다는 것이 이 필드의 존재 이유다 (계약 §2.2).
        challenges.put(
            completed(
                id = 30L,
                challengerId = other,
                deadline = now.minusDays(1),
                completedAt = now.minusDays(1),
                result = ChallengeResult.CHALLENGER_WIN,
            ),
        )

        val view = service.getActiveChallenges(me).single()

        assertEquals(ChallengeResult.CHALLENGER_WIN, view.result)
        assertEquals(Outcome.LOSE, view.myResult)
    }

    @Test
    fun `BOTH_LOSE 는 양쪽 모두에게 BOTH_LOSE 다`() {
        // 🔴 isChallenger 를 보지 않는 유일한 값이다. LOSE 로 접지 않고 그대로 내보낸다 —
        //    접으면 앱이 "둘 다 못 했다" 를 인증 상태로 역산해야 하고, 그게 판정 규칙의 사본이다.
        challenges.put(
            completed(
                id = 40L,
                challengerId = me,
                deadline = now.minusDays(2),
                completedAt = now.minusDays(2),
                result = ChallengeResult.BOTH_LOSE,
            ),
        )

        val mine = service.getActiveChallenges(me).single()
        val theirs = service.getActiveChallenges(other).single()

        assertEquals(Outcome.BOTH_LOSE, mine.myResult)
        assertEquals(Outcome.BOTH_LOSE, theirs.myResult)
        // 원본(역할 기준)도 양쪽 다 BOTH_LOSE 그대로다 — 두 축이 여기서만 값이 겹친다.
        assertEquals(ChallengeResult.BOTH_LOSE, mine.result)
        assertEquals(ChallengeResult.BOTH_LOSE, theirs.result)
    }

    @Test
    fun `진행 중이면 result 와 myResult 가 둘 다 null 이다`() {
        challenges.put(inProgress(id = 50L, challengerId = me, deadline = now.plusDays(1)))

        val view = service.getActiveChallenges(me).single()

        assertNull(view.result, "판정 전에 결과가 있으면 카드가 결과 표시로 잘못 갈아탄다")
        assertNull(view.myResult)
    }

    // ─── 4. 기존 매핑이 결과 카드에서도 유지된다 ──────────────────

    @Test
    fun `판정된 카드도 내 시점 미션과 인증 상태를 그대로 채운다`() {
        // 판정 배치가 미인증 측을 FAILED 로 바꾸므로 결과 카드의 myVerificationStatus 는
        // PENDING 이 아니라 FAILED 로 내려간다 (계약 §2.3 주의사항).
        challenges.put(
            completed(
                id = 60L,
                challengerId = other,
                deadline = now.minusDays(1),
                completedAt = now.minusDays(1),
                result = ChallengeResult.CHALLENGER_WIN,
            ),
        )
        verifications.put(verification(60L, other, VerificationStatus.VERIFIED))
        verifications.put(verification(60L, me, VerificationStatus.FAILED))

        val view = service.getActiveChallenges(me).single()

        // 내가 opponent 이므로 미션이 뒤집혀 채워진다 (기존 동작 — 결과 카드에서도 그대로다).
        assertEquals("상대 미션", view.myMission)
        assertEquals("챌린저 미션", view.opponentMission)
        assertEquals("민수", view.opponentNickname)
        assertEquals(VerificationStatus.FAILED, view.myVerificationStatus)
        assertEquals(VerificationStatus.VERIFIED, view.opponentVerificationStatus)
    }

    // ─── fixtures ────────────────────────────────────────────────

    private fun user(id: Long, nickname: String) = User(
        id = id,
        kakaoId = 9000_000L + id,
        nickname = nickname,
        profileImageUrl = null,
        phoneNumber = null,
        phoneVerified = false,
        fcmToken = null,
        status = UserStatus.ACTIVE,
        createdAt = now,
        updatedAt = now,
    )

    /** challenger 가 [challengerId], 상대는 자동으로 나머지 한 명이다 (1:1 대결). */
    private fun challenge(
        id: Long,
        challengerId: Long,
        deadline: LocalDateTime,
        status: ChallengeStatus,
        result: ChallengeResult?,
        completedAt: LocalDateTime?,
    ) = Challenge(
        id = id,
        challengerId = challengerId,
        opponentId = if (challengerId == me) other else me,
        challengerMission = "챌린저 미션",
        opponentMission = "상대 미션",
        betContent = "커피 사기",
        challengeDate = LocalDate.of(2026, 8, 24),
        deadline = deadline,
        status = status,
        result = result,
        createdAt = now.minusDays(10),
        completedAt = completedAt,
    )

    private fun inProgress(id: Long, challengerId: Long, deadline: LocalDateTime) =
        challenge(id, challengerId, deadline, ChallengeStatus.IN_PROGRESS, result = null, completedAt = null)

    private fun completed(
        id: Long,
        challengerId: Long,
        deadline: LocalDateTime,
        completedAt: LocalDateTime,
        result: ChallengeResult,
    ) = challenge(id, challengerId, deadline, ChallengeStatus.COMPLETED, result, completedAt)

    private fun verification(challengeId: Long, userId: Long, status: VerificationStatus) = Verification(
        id = null,
        challengeId = challengeId,
        userId = userId,
        status = status,
        photoUrl = if (status == VerificationStatus.VERIFIED) "/photo" else null,
        verifiedAt = if (status == VerificationStatus.VERIFIED) now.minusDays(1) else null,
        createdAt = now.minusDays(2),
    )

    // ─── in-memory fakes (중첩 — 패키지 내 이름 충돌 회피) ─────────

    /**
     * 실제 구현과 **같은 필터·정렬**을 지킨다 — 어긋나면 이 테스트가 거짓말을 한다.
     *
     * [lastSince] 는 서비스가 넘긴 `since` 를 그대로 붙잡아 둔다. 저장소가 그 값으로 무엇을
     * 걸렀는지가 아니라 **서비스가 무엇을 계산해 넘겼는지**를 보기 위한 것이다.
     */
    private class FakeChallengeStore : ChallengeRepository {
        private val store = LinkedHashMap<Long, Challenge>()

        var lastSince: LocalDateTime? = null
            private set

        fun put(c: Challenge) {
            store[c.id!!] = c
        }

        override fun findActiveByUser(userId: Long, statuses: Collection<ChallengeStatus>): List<Challenge> =
            store.values
                .filter { (it.challengerId == userId || it.opponentId == userId) && it.status in statuses }
                .sortedBy { it.deadline }

        override fun findRecentlyCompletedByUser(userId: Long, since: LocalDateTime): List<Challenge> {
            lastSince = since
            return store.values
                .filter { it.challengerId == userId || it.opponentId == userId }
                .filter { it.status == ChallengeStatus.COMPLETED }
                // 경계는 `>` 다 — 정확히 `since` 인 것은 이미 창 밖이다.
                .filter { c -> c.completedAt?.let { it > since } == true }
                .sortedByDescending { it.completedAt }
        }

        override fun findById(id: Long): Challenge? = store[id]

        override fun findReceivedPending(opponentId: Long, now: LocalDateTime): List<ReceivedChallenge> = emptyList()

        override fun findDueByStatus(statuses: Collection<ChallengeStatus>, now: LocalDateTime): List<Challenge> =
            emptyList()

        override fun findConflicting(
            userA: Long,
            userB: Long,
            challengeDate: LocalDate,
            statuses: Collection<ChallengeStatus>,
        ): List<Challenge> = emptyList()

        /** 이 서비스는 read-only 다 — 저장 경로를 타면 그 자체가 버그다. */
        override fun save(challenge: Challenge): Challenge = error("조회 전용 서비스가 저장을 시도했다")

        override fun saveAndFlush(challenge: Challenge): Challenge = error("조회 전용 서비스가 저장을 시도했다")

        override fun deleteById(id: Long) = error("조회 전용 서비스가 삭제를 시도했다")
    }

    private class FakeUsers : UserRepository {
        private val store = LinkedHashMap<Long, User>()

        fun put(u: User) {
            store[u.id!!] = u
        }

        override fun findAllByIds(ids: Collection<Long>): Map<Long, User> =
            ids.mapNotNull { store[it] }.associateBy { it.id!! }

        override fun findById(id: Long): User? = store[id]

        override fun findByKakaoId(kakaoId: Long): User? = store.values.firstOrNull { it.kakaoId == kakaoId }

        override fun save(user: User): User = user.also { put(it) }

        override fun updateRefreshTokenHash(userId: Long, hash: String?, issuedAt: LocalDateTime?) = Unit

        override fun clearFcmTokenOwnedByOthers(fcmToken: String, exceptUserId: Long): Int = 0

        override fun updateFcmToken(userId: Long, fcmToken: String?): Int = 1
    }

    private class FakeVerificationStore : VerificationRepository {
        private val store = mutableListOf<Verification>()

        fun put(v: Verification) {
            store.add(v)
        }

        override fun findAllByChallengeIds(challengeIds: Collection<Long>): List<Verification> =
            store.filter { it.challengeId in challengeIds }

        override fun save(verification: Verification): Verification = error("조회 전용 서비스가 저장을 시도했다")

        override fun saveAllAndFlush(verifications: Collection<Verification>): List<Verification> =
            error("조회 전용 서비스가 저장을 시도했다")
    }
}
