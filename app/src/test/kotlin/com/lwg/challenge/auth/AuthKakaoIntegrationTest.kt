package com.lwg.challenge.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
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
 * 카카오 로그인 (Authorization Code Flow) end-to-end 통합 테스트.
 *
 * - Testcontainers Postgres: Flyway V1__init.sql 적용됨 → 실제 스키마 사용.
 * - WireMock: Kakao `/oauth/token` + `/v2/user/me` 응답 스텁. (auth/api 둘 다 같은 호스트로 stub)
 * - 5 케이스:
 *   1. 신규 사용자 (phone 포함) → users INSERT, isNewUser=true, phone_verified=true
 *   2. 기존 사용자 → nickname 업데이트, isNewUser=false
 *   3. phone 미동의 → phone_number=null, phone_verified=false
 *   4. /oauth/token 4xx (invalid code) → HTTP 200 + code=701
 *   5. /v2/user/me 401 (token revoked between calls) → HTTP 200 + code=701
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
        private const val TEST_REDIRECT_URI = "http://localhost/oauth/kakao/callback"
        private const val TEST_REST_API_KEY = "test-rest-api-key"

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
            // 카카오 auth/api를 같은 WireMock 인스턴스로 묶어 stub. /oauth/token 과 /v2/user/me 경로가 다르므로 충돌 없음.
            registry.add("kakao.auth-base-url") { wireMockServer.baseUrl() }
            registry.add("kakao.api-base-url") { wireMockServer.baseUrl() }
            registry.add("kakao.rest-api-key") { TEST_REST_API_KEY }
            registry.add("kakao.client-secret") { "" }
            registry.add("kakao.redirect-uri") { TEST_REDIRECT_URI }
        }
    }

    @AfterEach
    fun resetState() {
        wireMockServer.resetAll()
        userRepository.deleteAll()
    }

    // ─── Fixtures ────────────────────────────────────────────────

    private fun stubKakaoTokenSuccess(code: String, accessToken: String) {
        stubFor(
            post(urlEqualTo("/oauth/token"))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=$code"))
                .withRequestBody(containing("client_id=$TEST_REST_API_KEY"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"token_type":"bearer","access_token":"$accessToken",""" +
                                """"expires_in":21599,"refresh_token":"kakao-refresh-$code",""" +
                                """"refresh_token_expires_in":5183999,"scope":"profile_nickname"}"""
                        )
                )
        )
    }

    private fun stubKakaoTokenInvalidCode(code: String) {
        stubFor(
            post(urlEqualTo("/oauth/token"))
                .withRequestBody(containing("code=$code"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"error":"invalid_grant","error_description":"authorization code not found"}""")
                )
        )
    }

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

    private fun postLogin(code: String): ResultActions =
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("code" to code)))
        )

    // ─── 1. 신규 사용자 (phone 포함) ────────────────────────────

    @Test
    fun `신규 사용자 - Kakao 응답의 정보로 users INSERT 되고 isNewUser=true`() {
        val code = "new-user-code"
        val kakaoAccessToken = "kakao-access-new"
        stubKakaoTokenSuccess(code = code, accessToken = kakaoAccessToken)
        stubKakaoUser(
            accessToken = kakaoAccessToken,
            id = 1000001L,
            nickname = "홍길동",
            profileImageUrl = "https://example.com/profile.png",
            phoneNumber = "+82 10-1234-5678",
        )

        postLogin(code)
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
        val firstCode = "existing-code-1"
        val firstAccess = "kakao-access-existing-1"
        stubKakaoTokenSuccess(code = firstCode, accessToken = firstAccess)
        stubKakaoUser(
            accessToken = firstAccess,
            id = 1000002L,
            nickname = "초기닉네임",
            profileImageUrl = null,
            phoneNumber = "+82 10-1111-2222",
        )
        postLogin(firstCode).andExpect(status().isOk)
        val afterFirst = userRepository.findByKakaoId(1000002L)!!
        val firstUserId = afterFirst.id

        // 2차: 동일 kakao_id + 다른 nickname → update
        wireMockServer.resetAll()
        val secondCode = "existing-code-2"
        val secondAccess = "kakao-access-existing-2"
        stubKakaoTokenSuccess(code = secondCode, accessToken = secondAccess)
        stubKakaoUser(
            accessToken = secondAccess,
            id = 1000002L,
            nickname = "변경된닉네임",
            profileImageUrl = "https://example.com/new.png",
            phoneNumber = "+82 10-1111-2222",
        )
        postLogin(secondCode)
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
        val code = "no-phone-code"
        val kakaoAccessToken = "kakao-access-no-phone"
        stubKakaoTokenSuccess(code = code, accessToken = kakaoAccessToken)
        stubKakaoUser(
            accessToken = kakaoAccessToken,
            id = 1000003L,
            nickname = "전화번호없음",
            profileImageUrl = null,
            phoneNumber = null,
            phoneNumberNeedsAgreement = true,
        )

        postLogin(code)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.isNewUser").value(true))

        val saved = userRepository.findByKakaoId(1000003L)!!
        assertNull(saved.phoneNumber)
        assertFalse(saved.phoneVerified)
    }

    // ─── 4. /oauth/token 4xx (invalid code) ─────────────────

    @Test
    fun `oauth token 4xx면 HTTP 200 + code=701`() {
        stubKakaoTokenInvalidCode("bad-code")

        postLogin("bad-code")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.DIALOG_ERROR))
            .andExpect(jsonPath("$.message").value("카카오 로그인이 만료되었습니다. 다시 시도해주세요"))

        // users에 아무것도 저장되지 않음
        assertEquals(0, userRepository.count())
    }

    // ─── 5. /v2/user/me 401 ─────────────────────────────────

    @Test
    fun `토큰 교환은 성공했지만 user me가 401이면 HTTP 200 + code=701`() {
        val code = "exchange-ok-but-userme-401"
        val accessToken = "kakao-access-revoked"
        stubKakaoTokenSuccess(code = code, accessToken = accessToken)
        stubKakaoUserUnauthorized(accessToken)

        postLogin(code)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.DIALOG_ERROR))

        assertEquals(0, userRepository.count())
    }
}
