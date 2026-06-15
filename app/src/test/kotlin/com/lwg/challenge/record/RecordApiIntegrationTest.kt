package com.lwg.challenge.record

import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.core.auth.JwtTokenProvider
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.infra.entity.auth.UserEntity
import com.lwg.challenge.infra.entity.userrecord.UserRecordEntity
import com.lwg.challenge.infra.jpa.auth.UserJpaRepository
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
 * GET /api/v1/record 통합 테스트.
 *
 * - Testcontainers Postgres + Flyway → 실제 스키마.
 * - JWT 는 JwtTokenProvider 로 직접 발급해 Authorization 헤더에 주입.
 * - 시나리오:
 *   1. 신규 사용자 — user_stats row 없음 → 0/0/0/0.
 *   2. 전적 row 가 있는 사용자 — 그대로 응답.
 *   3. 위조 토큰 → HTTP 200 + body.code=401.
 *
 * Docker 미가용 환경에서는 전체 skip.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIf(
    value = "com.lwg.challenge.record.RecordApiIntegrationTest#isDockerAvailable",
    disabledReason = "Docker 데몬이 필요합니다 (Testcontainers Postgres). Docker Desktop 실행 후 재시도하세요.",
)
class RecordApiIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var userRepository: UserJpaRepository

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

    private fun accessTokenFor(userId: Long): String = jwtTokenProvider.generateAccessToken(userId)

    private fun getRecord(token: String?) = mockMvc.perform(
        MockMvcRequestBuilders.get("/api/v1/record")
            .apply { if (token != null) header("Authorization", "Bearer $token") },
    )

    @Test
    fun `신규 사용자 - user_stats row 없으면 0 채움`() {
        val me = newUser(kakaoId = 9000001L, nickname = "신규유저")
        val token = accessTokenFor(me.id!!)

        getRecord(token)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.win").value(0))
            .andExpect(jsonPath("$.data.lose").value(0))
            .andExpect(jsonPath("$.data.draw").value(0))
            .andExpect(jsonPath("$.data.currentStreak").value(0))
    }

    @Test
    fun `기존 전적 row 그대로 응답`() {
        val me = newUser(kakaoId = 9000010L, nickname = "나")
        userRecordRepository.save(
            UserRecordEntity(
                userId = me.id!!,
                totalChallenges = 12,
                wins = 7,
                losses = 3,
                draws = 2,
                currentStreak = 3,
                maxStreak = 5,
                updatedAt = LocalDateTime.now(),
            ),
        )

        val token = accessTokenFor(me.id!!)
        getRecord(token)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.win").value(7))
            .andExpect(jsonPath("$.data.lose").value(3))
            .andExpect(jsonPath("$.data.draw").value(2))
            .andExpect(jsonPath("$.data.currentStreak").value(3))
    }

    @Test
    fun `토큰이 위조 만료 또는 없으면 HTTP 200 + body code 401`() {
        getRecord(token = "not.a.valid.jwt.token")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))
    }
}
