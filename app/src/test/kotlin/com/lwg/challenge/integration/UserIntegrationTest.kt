package com.lwg.challenge.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.core.auth.JwtTokenProvider
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.infra.entity.auth.UserEntity
import com.lwg.challenge.infra.jpa.auth.UserJpaRepository
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDateTime

/**
 * 본인 정보 조회 end-to-end 통합 테스트 (spec-user-info §4.6).
 *
 * - Testcontainers Postgres + Flyway V1__init.sql → 실제 users 스키마.
 * - 4 시나리오: 정상 응답(4 필드), 미인증 401, 토큰 userId DB 부재 401, profile_image_url null.
 *
 * Docker 미가용 시 전체 skip.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIf(
    value = "com.lwg.challenge.integration.UserIntegrationTest#isDockerAvailable",
    disabledReason = "Docker 데몬이 필요합니다 (Testcontainers Postgres). Docker Desktop 실행 후 재시도하세요.",
)
class UserIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var userRepository: UserJpaRepository

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
        userRepository.deleteAll()
    }

    // ─── Fixtures ────────────────────────────────────────────────

    private fun newUser(
        kakaoId: Long,
        nickname: String,
        profileImageUrl: String? = null,
        status: UserStatus = UserStatus.ACTIVE,
    ): UserEntity {
        val now = LocalDateTime.now()
        return userRepository.save(
            UserEntity(
                id = null,
                kakaoId = kakaoId,
                nickname = nickname,
                profileImageUrl = profileImageUrl,
                phoneNumber = null,
                phoneVerified = false,
                fcmToken = null,
                status = status.name,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun accessTokenFor(userId: Long): String = jwtTokenProvider.generateAccessToken(userId)

    private fun getMe(token: String?): ResultActions =
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/users/me")
                .apply { if (token != null) header("Authorization", "Bearer $token") },
        )

    // ─── 1. 정상 응답 — 4 필드 모두 ──────────────────────────────

    @Test
    fun `정상 응답 - id kakaoId nickname profileImageUrl 4 필드 반환`() {
        val me = newUser(
            kakaoId = 4883170475L,
            nickname = "이우건",
            profileImageUrl = "http://img1.kakaocdn.net/u/42.jpg",
        )
        val token = accessTokenFor(me.id!!)

        getMe(token)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.id").value(me.id))
            .andExpect(jsonPath("$.data.kakaoId").value(4883170475L))
            .andExpect(jsonPath("$.data.nickname").value("이우건"))
            .andExpect(jsonPath("$.data.profileImageUrl").value("http://img1.kakaocdn.net/u/42.jpg"))
    }

    // ─── 2. 미인증 401 ───────────────────────────────────────────

    @Test
    fun `미인증 토큰 없으면 HTTP 401 + code 401`() {
        getMe(token = null)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))
    }

    // ─── 3. 토큰의 userId 가 DB 에 없음 401 ──────────────────────

    @Test
    fun `토큰 userId 가 DB 에 없으면 HTTP 401 + code 401`() {
        // DB 에 존재하지 않는 userId 로 발급된 토큰 (회원탈퇴/삭제 케이스)
        val token = accessTokenFor(999_999L)

        getMe(token)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))
    }

    // ─── 4. profile_image_url null 사용자 ────────────────────────

    @Test
    fun `profileImageUrl 이 null 인 사용자 - 응답 profileImageUrl null`() {
        val me = newUser(
            kakaoId = 1234567890L,
            nickname = "프사없음",
            profileImageUrl = null,
        )
        val token = accessTokenFor(me.id!!)

        getMe(token)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.id").value(me.id))
            .andExpect(jsonPath("$.data.nickname").value("프사없음"))
            .andExpect(jsonPath("$.data.profileImageUrl").doesNotExist())
    }
}
