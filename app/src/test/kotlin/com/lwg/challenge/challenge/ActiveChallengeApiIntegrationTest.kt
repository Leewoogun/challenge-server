package com.lwg.challenge.challenge

import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.core.auth.JwtTokenProvider
import com.lwg.challenge.domain.challenge.ChallengeResult
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.domain.verification.VerificationStatus
import com.lwg.challenge.infra.entity.auth.UserEntity
import com.lwg.challenge.infra.entity.challenge.ChallengeEntity
import com.lwg.challenge.infra.entity.verification.VerificationEntity
import com.lwg.challenge.infra.jpa.auth.UserJpaRepository
import com.lwg.challenge.infra.jpa.challenge.ChallengeJpaRepository
import com.lwg.challenge.infra.jpa.verification.VerificationJpaRepository
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
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * GET /api/v1/challenges/active 통합 테스트.
 *
 * - Testcontainers Postgres + Flyway → 실제 스키마.
 * - 시나리오:
 *   1. 진행 중 챌린지 없음 → 빈 배열.
 *   2. 진행 중 챌린지 2건 — challenger/opponent 매핑, COMPLETED/타인 챌린지 제외, deadline ASC, verification 매핑.
 *   3. 위조 토큰 → HTTP 200 + body.code=401.
 *
 * Docker 미가용 환경에서는 전체 skip.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIf(
    value = "com.lwg.challenge.challenge.ActiveChallengeApiIntegrationTest#isDockerAvailable",
    disabledReason = "Docker 데몬이 필요합니다 (Testcontainers Postgres). Docker Desktop 실행 후 재시도하세요.",
)
class ActiveChallengeApiIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var userRepository: UserJpaRepository

    @Autowired
    lateinit var challengeRepository: ChallengeJpaRepository

    @Autowired
    lateinit var verificationRepository: VerificationJpaRepository

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
        verificationRepository.deleteAll()
        challengeRepository.deleteAll()
        userRepository.deleteAll()
    }

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

    private fun newChallenge(
        challengerId: Long,
        opponentId: Long,
        challengerMission: String,
        opponentMission: String,
        betContent: String,
        deadline: LocalDateTime,
        status: ChallengeStatus,
        result: ChallengeResult? = null,
    ): ChallengeEntity {
        val now = LocalDateTime.now()
        return challengeRepository.save(
            ChallengeEntity(
                id = null,
                challengerId = challengerId,
                opponentId = opponentId,
                challengerMission = challengerMission,
                opponentMission = opponentMission,
                betContent = betContent,
                challengeDate = LocalDate.now(),
                deadline = deadline,
                status = status.name,
                result = result?.name,
                createdAt = now,
                completedAt = if (status == ChallengeStatus.COMPLETED) now else null,
            ),
        )
    }

    private fun newVerification(
        challengeId: Long,
        userId: Long,
        status: VerificationStatus,
        photoUrl: String? = null,
    ): VerificationEntity {
        val now = LocalDateTime.now()
        return verificationRepository.save(
            VerificationEntity(
                id = null,
                challengeId = challengeId,
                userId = userId,
                status = status.name,
                photoUrl = photoUrl ?: if (status == VerificationStatus.VERIFIED) "https://e.com/p.png" else null,
                verifiedAt = if (status == VerificationStatus.VERIFIED) now else null,
                createdAt = now,
            ),
        )
    }

    private fun accessTokenFor(userId: Long): String = jwtTokenProvider.generateAccessToken(userId)

    private fun getActive(token: String?) = mockMvc.perform(
        MockMvcRequestBuilders.get("/api/v1/challenges/active")
            .apply { if (token != null) header("Authorization", "Bearer $token") },
    )

    @Test
    fun `진행 중 챌린지 없으면 빈 배열`() {
        val me = newUser(kakaoId = 9000001L, nickname = "신규유저")
        val token = accessTokenFor(me.id!!)

        getActive(token)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.activeChallenges").isArray)
            .andExpect(jsonPath("$.data.activeChallenges.length()").value(0))
    }

    @Test
    fun `challenger 와 opponent 매핑, COMPLETED 제외, deadline ASC, verification 매핑`() {
        val me = newUser(kakaoId = 9000010L, nickname = "나")
        val minsoo = newUser(kakaoId = 9000011L, nickname = "민수")
        val jiyeon = newUser(kakaoId = 9000012L, nickname = "지연")
        val third = newUser(kakaoId = 9000013L, nickname = "타인")

        // 카드 A: 내가 opponent, deadline 더 이름. verification: 나=PENDING, 상대=VERIFIED
        val cA = newChallenge(
            challengerId = minsoo.id!!,
            opponentId = me.id!!,
            challengerMission = "책 30페이지 읽기",
            opponentMission = "오늘 운동 1시간 하기",
            betContent = "커피 사기",
            deadline = LocalDateTime.of(2026, 5, 30, 0, 0, 0),
            status = ChallengeStatus.IN_PROGRESS,
        )
        newVerification(cA.id!!, minsoo.id!!, VerificationStatus.VERIFIED)
        newVerification(cA.id!!, me.id!!, VerificationStatus.PENDING)

        // 카드 B: 내가 challenger, deadline 더 늦음. verification 없음 → 양측 PENDING
        val cB = newChallenge(
            challengerId = me.id!!,
            opponentId = jiyeon.id!!,
            challengerMission = "물 2L 마시기",
            opponentMission = "스트레칭 20분",
            betContent = "저녁 사기",
            deadline = LocalDateTime.of(2026, 6, 5, 0, 0, 0),
            status = ChallengeStatus.IN_PROGRESS,
        )

        // 제외: 내가 참여한 COMPLETED
        newChallenge(
            challengerId = me.id!!,
            opponentId = minsoo.id!!,
            challengerMission = "이미 끝난 미션",
            opponentMission = "이미 끝난 미션 상대",
            betContent = "이미 보상 받음",
            deadline = LocalDateTime.of(2026, 4, 1, 0, 0, 0),
            status = ChallengeStatus.COMPLETED,
            result = ChallengeResult.CHALLENGER_WIN,
        )

        // 제외: 타인끼리 IN_PROGRESS
        newChallenge(
            challengerId = minsoo.id!!,
            opponentId = third.id!!,
            challengerMission = "타인 미션",
            opponentMission = "타인 상대 미션",
            betContent = "관계없음",
            deadline = LocalDateTime.of(2026, 5, 25, 0, 0, 0),
            status = ChallengeStatus.IN_PROGRESS,
        )

        val token = accessTokenFor(me.id!!)

        getActive(token)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.activeChallenges.length()").value(2))
            .andExpect(jsonPath("$.data.activeChallenges[0].challengeId").value(cA.id!!))
            .andExpect(jsonPath("$.data.activeChallenges[0].myMission").value("오늘 운동 1시간 하기"))
            .andExpect(jsonPath("$.data.activeChallenges[0].opponentNickname").value("민수"))
            .andExpect(jsonPath("$.data.activeChallenges[0].opponentMission").value("책 30페이지 읽기"))
            .andExpect(jsonPath("$.data.activeChallenges[0].myVerificationStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.activeChallenges[0].opponentVerificationStatus").value("VERIFIED"))
            .andExpect(jsonPath("$.data.activeChallenges[0].bet").value("커피 사기"))
            .andExpect(jsonPath("$.data.activeChallenges[0].deadline").value("2026-05-30T00:00:00Z"))
            .andExpect(jsonPath("$.data.activeChallenges[1].challengeId").value(cB.id!!))
            .andExpect(jsonPath("$.data.activeChallenges[1].myMission").value("물 2L 마시기"))
            .andExpect(jsonPath("$.data.activeChallenges[1].opponentNickname").value("지연"))
            .andExpect(jsonPath("$.data.activeChallenges[1].opponentMission").value("스트레칭 20분"))
            .andExpect(jsonPath("$.data.activeChallenges[1].myVerificationStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.activeChallenges[1].opponentVerificationStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.activeChallenges[1].bet").value("저녁 사기"))
            .andExpect(jsonPath("$.data.activeChallenges[1].deadline").value("2026-06-05T00:00:00Z"))
    }

    @Test
    fun `토큰이 위조 만료 또는 없으면 HTTP 401 + body code 401`() {
        getActive(token = "not.a.valid.jwt.token")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))
    }
}
