package com.lwg.challenge.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.lwg.challenge.api.common.ResponseCode
import com.lwg.challenge.core.hash.PhoneHasher
import com.lwg.challenge.infra.auth.UserJpaRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * 카카오 로그인 (Kakao SDK access_token 방식) end-to-end 통합 테스트.
 *
 * - Testcontainers Postgres: Flyway V1__init.sql 적용됨 → 실제 스키마 사용.
 * - WireMock: Kakao `/v2/user/me` 응답만 스텁 (서버는 더 이상 /oauth/token 호출하지 않음).
 * - 5 케이스:
 *   1. 신규 사용자 (phone 포함) → users INSERT, isNewUser=true, phone_verified=true
 *   2. 기존 사용자 → nickname 업데이트, isNewUser=false
 *   3. phone 미동의 → phone_number=null, phone_verified=false
 *   4. /v2/user/me 401 (token revoked/invalid) → HTTP 200 + code=701
 *   5. /v2/user/me 5xx → 1회 재시도 후 HTTP 200 + code=703
 *
 * Docker 미가용 환경(로컬 Docker Desktop 꺼짐 등)에서는 JUnit `@EnabledIf`로 테스트 전체를 skip.
 * CI에서는 docker-in-docker 세팅 필요.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIf(
    value = "com.lwg.challenge.auth.AuthKakaoIntegrationTest#isDockerAvailable",
    disabledReason = "Docker 데몬이 필요합니다 (Testcontainers Postgres). Docker Desktop 실행 후 재시도하세요.",
)
class AuthKakaoIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var userRepository: UserJpaRepository

    companion object {

        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("challenge_test")
            .withUsername("test")
            .withPassword("test")

        @JvmStatic
        lateinit var wireMockServer: WireMockServer

        /**
         * `@EnabledIf`가 참조하는 함수. Docker 데몬 가용성 검사.
         * Kotlin companion object 함수는 JVM static이 아니므로 `@JvmStatic`을 달아야 JUnit이 찾을 수 있다.
         */
        @JvmStatic
        fun isDockerAvailable(): Boolean =
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

        @BeforeAll
        @JvmStatic
        fun startInfra() {
            postgres.start()
            wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
            wireMockServer.start()
        }

        @AfterAll
        @JvmStatic
        fun stopInfra() {
            if (::wireMockServer.isInitialized) wireMockServer.stop()
            if (postgres.isRunning) postgres.stop()
        }

        @DynamicPropertySource
        @JvmStatic
        fun registerProps(registry: DynamicPropertyRegistry) {
            // @DynamicPropertySource는 context 초기화 전 호출. 위 @BeforeAll보다 먼저 실행되므로
            // 여기서 컨테이너를 확실히 시작해둔다.
            if (!postgres.isRunning) postgres.start()
            if (!::wireMockServer.isInitialized) {
                wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
                wireMockServer.start()
            }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.flyway.url") { postgres.jdbcUrl }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
            registry.add("jwt.secret") { "integration-test-jwt-secret-minimum-32-bytes-xxxxxx" }
            // SDK 방식이라 서버에는 api-base-url 만 필요. /v2/user/me 호출을 WireMock 으로 가로챈다.
            registry.add("kakao.api-base-url") { wireMockServer.baseUrl() }
        }
    }

    @AfterEach
    fun resetState() {
        wireMockServer.resetAll()
        userRepository.deleteAll()
    }

    // ─── Fixtures ────────────────────────────────────────────────

    private fun stubKakaoUser(
        accessToken: String,
        id: Long,
        nickname: String?,
        profileImageUrl: String?,
        phoneNumber: String?,
        phoneNumberNeedsAgreement: Boolean = false,
    ) {
        val kakaoAccountJson = buildString {
            append("{")
            append("\"phone_number_needs_agreement\":$phoneNumberNeedsAgreement,")
            if (phoneNumber != null) append("\"phone_number\":\"$phoneNumber\",")
            append("\"profile_needs_agreement\":false,")
            append("\"profile\":{")
            if (nickname != null) append("\"nickname\":\"$nickname\"")
            if (nickname != null && profileImageUrl != null) append(",")
            if (profileImageUrl != null) append("\"profile_image_url\":\"$profileImageUrl\"")
            append("}")
            append("}")
        }
        val body = """{"id":$id,"kakao_account":$kakaoAccountJson}"""
        stubFor(
            get(urlEqualTo("/v2/user/me"))
                .withHeader("Authorization", equalTo("Bearer $accessToken"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)
                )
        )
    }

    private fun stubKakaoUserUnauthorized(accessToken: String) {
        stubFor(
            get(urlEqualTo("/v2/user/me"))
                .withHeader("Authorization", equalTo("Bearer $accessToken"))
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"msg":"this access token is invalid","code":-401}""")
                )
        )
    }

    private fun stubKakaoUserServerError(accessToken: String) {
        stubFor(
            get(urlEqualTo("/v2/user/me"))
                .withHeader("Authorization", equalTo("Bearer $accessToken"))
                .willReturn(
                    aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"msg":"service unavailable"}""")
                )
        )
    }

    private fun postLogin(kakaoAccessToken: String): ResultActions =
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("kakaoAccessToken" to kakaoAccessToken)))
        )

    // ─── 1. 신규 사용자 (phone 포함) ────────────────────────────

    @Test
    fun `신규 사용자 - Kakao 응답의 정보로 users INSERT 되고 isNewUser=true`() {
        val kakaoAccessToken = "kakao-access-new"
        stubKakaoUser(
            accessToken = kakaoAccessToken,
            id = 1000001L,
            nickname = "홍길동",
            profileImageUrl = "https://example.com/profile.png",
            phoneNumber = "+82 10-1234-5678",
        )

        postLogin(kakaoAccessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.isNewUser").value(true))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty)
            .andExpect(jsonPath("$.data.refreshToken").isNotEmpty)
            .andExpect(jsonPath("$.data.userId").isNumber)

        val saved = userRepository.findByKakaoId(1000001L)
        assertNotNull(saved)
        assertEquals("홍길동", saved!!.nickname)
        assertEquals("https://example.com/profile.png", saved.profileImageUrl)
        assertEquals(PhoneHasher.hashPhone("+82 10-1234-5678"), saved.phoneNumber)
        assertTrue(saved.phoneVerified)
    }

    // ─── 2. 기존 사용자 업데이트 ──────────────────────────────

    @Test
    fun `기존 사용자 - nickname 갱신되고 isNewUser=false`() {
        // 1차: 신규 가입
        val firstAccess = "kakao-access-existing-1"
        stubKakaoUser(
            accessToken = firstAccess,
            id = 1000002L,
            nickname = "초기닉네임",
            profileImageUrl = null,
            phoneNumber = "+82 10-1111-2222",
        )
        postLogin(firstAccess).andExpect(status().isOk)
        val afterFirst = userRepository.findByKakaoId(1000002L)!!
        val firstUserId = afterFirst.id

        // 2차: 동일 kakao_id + 다른 nickname → update
        wireMockServer.resetAll()
        val secondAccess = "kakao-access-existing-2"
        stubKakaoUser(
            accessToken = secondAccess,
            id = 1000002L,
            nickname = "변경된닉네임",
            profileImageUrl = "https://example.com/new.png",
            phoneNumber = "+82 10-1111-2222",
        )
        postLogin(secondAccess)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.isNewUser").value(false))
            .andExpect(jsonPath("$.data.userId").value(firstUserId))

        val afterSecond = userRepository.findByKakaoId(1000002L)!!
        assertEquals(firstUserId, afterSecond.id)
        assertEquals("변경된닉네임", afterSecond.nickname)
        assertEquals("https://example.com/new.png", afterSecond.profileImageUrl)
    }

    // ─── 3. phone 미동의 ────────────────────────────────────

    @Test
    fun `phone_number_needs_agreement=true면 phone_number=null, phone_verified=false`() {
        val kakaoAccessToken = "kakao-access-no-phone"
        stubKakaoUser(
            accessToken = kakaoAccessToken,
            id = 1000003L,
            nickname = "전화번호없음",
            profileImageUrl = null,
            phoneNumber = null,
            phoneNumberNeedsAgreement = true,
        )

        postLogin(kakaoAccessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.isNewUser").value(true))

        val saved = userRepository.findByKakaoId(1000003L)!!
        assertNull(saved.phoneNumber)
        assertFalse(saved.phoneVerified)
    }

    // ─── 4. /v2/user/me 401 → code=701 ─────────────────────

    @Test
    fun `user me가 401이면 HTTP 200 + code=701`() {
        val accessToken = "kakao-access-revoked"
        stubKakaoUserUnauthorized(accessToken)

        postLogin(accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.DIALOG_ERROR))
            .andExpect(jsonPath("$.message").value("카카오 로그인이 만료되었습니다. 다시 시도해주세요"))

        // users 에 아무것도 저장되지 않음
        assertEquals(0, userRepository.count())
    }

    // ─── 5. /v2/user/me 5xx → 1회 재시도 후 code=703 ───────

    @Test
    fun `user me가 5xx면 1회 재시도 후 HTTP 200 + code=703`() {
        val accessToken = "kakao-access-server-error"
        stubKakaoUserServerError(accessToken)

        postLogin(accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.FULL_SCREEN_ERROR_B))
            .andExpect(jsonPath("$.message").value("일시적인 장애로 로그인할 수 없습니다"))

        // 1차 호출 + 1회 재시도 = 총 2회 호출되었는지 검증
        val matchedCalls = wireMockServer.allServeEvents.count { event ->
            event.request.url == "/v2/user/me"
        }
        assertEquals(2, matchedCalls, "5xx 응답에 대해 정확히 1회 재시도가 발생해야 한다 (총 2회 호출)")

        assertEquals(0, userRepository.count())
    }
}
