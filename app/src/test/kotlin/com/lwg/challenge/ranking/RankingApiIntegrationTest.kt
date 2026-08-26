package com.lwg.challenge.ranking

import com.lwg.challenge.core.auth.JwtTokenProvider
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.friend.FriendshipStatus
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.infra.entity.auth.UserEntity
import com.lwg.challenge.infra.entity.friend.FriendshipEntity
import com.lwg.challenge.infra.entity.userrecord.UserRecordEntity
import com.lwg.challenge.infra.jpa.auth.UserJpaRepository
import com.lwg.challenge.infra.jpa.friend.FriendshipJpaRepository
import com.lwg.challenge.infra.jpa.userrecord.UserRecordJpaRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDateTime

/**
 * GET /api/v1/rankings/losers 통합 테스트.
 *
 * ## 🔴 native query 는 슬라이스 테스트가 못 덮는다
 *
 * `LoserRankingJpaRepository.findMeAndFriendStats` 는 문자열 SQL 하나다. `LEFT JOIN user_stats`
 * 의 `COALESCE` 0 폴백과 `friendships` accepted 양방향 서브쿼리(`CASE WHEN f.requester_id = :me
 * THEN f.receiver_id ELSE f.requester_id END`)는 **실제 SQL 이 돌아야만** 검증된다 —
 * `RankingControllerTest` 는 서비스를 mock 하므로 이 SQL 을 한 글자도 실행하지 않고,
 * `LoserRankingTest` 는 이미 조회된 결과부터 시작한다. INNER JOIN 으로 바뀌거나
 * `AND (f.requester_id = :me OR f.receiver_id = :me)` 한 줄이 빠져도 다른 테스트는 전부 통과한다.
 *
 * ⚠️ 지금 이 환경엔 컨테이너 런타임이 없어 **전체 skip 된다.** 그래도 써 두는 이유는,
 * 컨테이너 런타임이 생기는 순간 이 검증이 **자동으로 켜지기 때문**이다. 안 써 두면 그때도
 * 그대로 사각지대로 남는다.
 *
 * 시나리오:
 *  1. 나 + accepted 친구만 나온다 (PENDING / REJECTED / 남남 제외).
 *  2. `friendships` 양방향 — 내가 requester 인 경우와 receiver 인 경우 둘 다 잡힌다.
 *  3. `user_stats` row 가 없는 친구가 0 으로 채워져 나온다 (목록에서 사라지지 않는다).
 *  4. 친구 0명이면 나 1건만 나온다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIf(
    value = "com.lwg.challenge.ranking.RankingApiIntegrationTest#isDockerAvailable",
    disabledReason = "Docker 데몬이 필요합니다 (Testcontainers Postgres). Docker Desktop 실행 후 재시도하세요.",
)
class RankingApiIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userRepository: UserJpaRepository

    @Autowired
    lateinit var friendshipJpaRepository: FriendshipJpaRepository

    @Autowired
    lateinit var userRecordRepository: UserRecordJpaRepository

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    companion object {

        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("challenge_test")
            .withUsername("test")
            .withPassword("test")

        @JvmStatic
        fun isDockerAvailable(): Boolean =
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

        @BeforeAll
        @JvmStatic
        fun startInfra() {
            if (!postgres.isRunning) postgres.start()
        }

        @AfterAll
        @JvmStatic
        fun stopInfra() {
            if (postgres.isRunning) postgres.stop()
        }

        @DynamicPropertySource
        @JvmStatic
        fun registerProps(registry: DynamicPropertyRegistry) {
            if (!postgres.isRunning) postgres.start()
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.flyway.url") { postgres.jdbcUrl }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
            registry.add("jwt.secret") { "integration-test-jwt-secret-minimum-32-bytes-xxxxxx" }
            registry.add("kakao.api-base-url") { "http://localhost:0" }
        }
    }

    @AfterEach
    fun resetState() {
        userRecordRepository.deleteAll()
        friendshipJpaRepository.deleteAll()
        userRepository.deleteAll()
    }

    // ─── Fixtures ────────────────────────────────────────────────

    private fun newUser(kakaoId: Long, nickname: String): UserEntity {
        val now = LocalDateTime.now()
        return userRepository.save(
            UserEntity(
                id = null,
                kakaoId = kakaoId,
                nickname = nickname,
                profileImageUrl = null,
                phoneNumber = null,
                phoneVerified = false,
                fcmToken = null,
                status = UserStatus.ACTIVE.name,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun saveFriendship(requesterId: Long, receiverId: Long, status: FriendshipStatus) {
        friendshipJpaRepository.save(
            FriendshipEntity(
                id = null,
                requesterId = requesterId,
                receiverId = receiverId,
                status = status.name,
                createdAt = LocalDateTime.now(),
                acceptedAt = if (status == FriendshipStatus.ACCEPTED) LocalDateTime.now() else null,
            ),
        )
    }

    /** `user_stats` row. **호출하지 않으면 row 자체가 없다** — 그게 시나리오 3 의 조건이다. */
    private fun saveStats(userId: Long, losses: Int, totalChallenges: Int, currentLossStreak: Int) {
        userRecordRepository.save(
            UserRecordEntity(
                userId = userId,
                totalChallenges = totalChallenges,
                wins = totalChallenges - losses,
                losses = losses,
                draws = 0,
                currentStreak = 0,
                maxStreak = 0,
                currentLossStreak = currentLossStreak,
                maxLossStreak = currentLossStreak,
                updatedAt = LocalDateTime.now(),
            ),
        )
    }

    private fun getRanking(userId: Long) = mockMvc.perform(
        MockMvcRequestBuilders.get("/api/v1/rankings/losers")
            .header("Authorization", "Bearer ${jwtTokenProvider.generateAccessToken(userId)}"),
    )

    // ─── 시나리오 ─────────────────────────────────────────────────

    /**
     * accepted 친구만 목록에 든다. PENDING/REJECTED 는 아직(또는 이미) 친구가 아니고, 남남은
     * 애초에 `friendships` row 가 없다. 서브쿼리의 `f.status = 'ACCEPTED'` 가 빠지면 **거절한
     * 사람이 내 랭킹에 등장한다.**
     */
    @Test
    fun `나와 accepted 친구만 나온다 - PENDING REJECTED 와 남남은 제외`() {
        val me = newUser(9100001L, "나")
        val friend = newUser(9100002L, "수락친구")
        val pending = newUser(9100003L, "대기중")
        val rejected = newUser(9100004L, "거절됨")
        newUser(9100005L, "남남")

        saveFriendship(me.id!!, friend.id!!, FriendshipStatus.ACCEPTED)
        saveFriendship(me.id!!, pending.id!!, FriendshipStatus.PENDING)
        saveFriendship(rejected.id!!, me.id!!, FriendshipStatus.REJECTED)

        saveStats(friend.id!!, losses = 5, totalChallenges = 10, currentLossStreak = 2)
        saveStats(me.id!!, losses = 1, totalChallenges = 10, currentLossStreak = 0)

        getRanking(me.id!!)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.rankings.length()").value(2))
            .andExpect(jsonPath("$.data.rankings[0].userId").value(friend.id!!.toInt()))
            .andExpect(jsonPath("$.data.rankings[0].isMe").value(false))
            .andExpect(jsonPath("$.data.rankings[1].userId").value(me.id!!.toInt()))
            .andExpect(jsonPath("$.data.rankings[1].isMe").value(true))
    }

    /**
     * 🔴 `friendships` 는 **단방향 1 row** 다 (`FriendshipEntity` KDoc). 그래서 서브쿼리가
     * `CASE WHEN f.requester_id = :me THEN f.receiver_id ELSE f.requester_id END` 로 양쪽을
     * 뒤집어 본다. 한쪽만 보면 **내가 요청을 받아서 맺어진 친구가 통째로 사라진다** — 랭킹이
     * 절반만 나오는데 앱은 그걸 알 방법이 없다.
     */
    @Test
    fun `friendships 는 양방향이다 - 내가 requester 든 receiver 든 잡힌다`() {
        val me = newUser(9100011L, "나")
        val iRequested = newUser(9100012L, "내가보냄")
        val iReceived = newUser(9100013L, "내가받음")

        saveFriendship(me.id!!, iRequested.id!!, FriendshipStatus.ACCEPTED)
        saveFriendship(iReceived.id!!, me.id!!, FriendshipStatus.ACCEPTED)

        saveStats(iRequested.id!!, losses = 9, totalChallenges = 9, currentLossStreak = 9)
        saveStats(iReceived.id!!, losses = 5, totalChallenges = 10, currentLossStreak = 1)
        saveStats(me.id!!, losses = 1, totalChallenges = 10, currentLossStreak = 0)

        getRanking(me.id!!)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.rankings.length()").value(3))
            .andExpect(jsonPath("$.data.rankings[0].userId").value(iRequested.id!!.toInt()))
            .andExpect(jsonPath("$.data.rankings[1].userId").value(iReceived.id!!.toInt()))
            .andExpect(jsonPath("$.data.rankings[2].userId").value(me.id!!.toInt()))
    }

    /**
     * 🔴 **`user_stats` row 가 없는 친구가 목록에서 사라지면 안 된다.** 챌린지를 한 번도 안 한
     * 친구가 여기 해당하는데, `LEFT JOIN` 을 `INNER JOIN` 으로 바꾸면 그 친구가 통째로 빠진다.
     * `COALESCE` 0 폴백이 실제로 도는지도 여기서만 확인된다 — 셋 다 0 으로 나와야 한다.
     */
    @Test
    fun `user_stats row 가 없는 친구도 0 으로 채워져 나온다`() {
        val me = newUser(9100021L, "나")
        val newbie = newUser(9100022L, "챌린지미경험")

        saveFriendship(me.id!!, newbie.id!!, FriendshipStatus.ACCEPTED)
        saveStats(me.id!!, losses = 3, totalChallenges = 10, currentLossStreak = 1)
        // newbie 는 saveStats 를 호출하지 않는다 — user_stats row 자체가 없는 상태.

        getRanking(me.id!!)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.rankings.length()").value(2))
            .andExpect(jsonPath("$.data.rankings[1].userId").value(newbie.id!!.toInt()))
            .andExpect(jsonPath("$.data.rankings[1].nickname").value("챌린지미경험"))
            .andExpect(jsonPath("$.data.rankings[1].losses").value(0))
            .andExpect(jsonPath("$.data.rankings[1].lossRate").value(0))
            .andExpect(jsonPath("$.data.rankings[1].currentLossStreak").value(0))
            // totalChallenges 도 COALESCE 0 폴백을 탄다 — 앱은 이 값으로 "아직 기록 없음" 을 판정한다.
            .andExpect(jsonPath("$.data.rankings[1].totalChallenges").value(0))
    }

    /** 계약 §5 불변식 — `WHERE u.id = :me OR ...` 의 `u.id = :me` 쪽이 나를 항상 넣는다. */
    @Test
    fun `친구가 0명이면 나 1건만 나온다`() {
        val me = newUser(9100031L, "혼자")
        newUser(9100032L, "남남")

        getRanking(me.id!!)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.rankings").isArray)
            .andExpect(jsonPath("$.data.rankings.length()").value(1))
            .andExpect(jsonPath("$.data.rankings[0].rank").value(1))
            .andExpect(jsonPath("$.data.rankings[0].userId").value(me.id!!.toInt()))
            .andExpect(jsonPath("$.data.rankings[0].isMe").value(true))
            .andExpect(jsonPath("$.data.rankings[0].losses").value(0))
            .andExpect(jsonPath("$.data.rankings[0].lossRate").value(0))
    }
}
