package com.lwg.challenge.controller.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.controller.auth.dto.KakaoLoginRequest
import com.lwg.challenge.controller.auth.dto.RefreshRequest
import com.lwg.challenge.controller.common.exception.GlobalExceptionHandler
import com.lwg.challenge.core.auth.JwtTokenProvider
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.DialogException
import com.lwg.challenge.service.auth.AuthService
import com.lwg.challenge.service.auth.LoginResult
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * AuthController 슬라이스 테스트.
 *
 * - `@WebMvcTest` 로 컨트롤러·예외 핸들러만 로드 (DB 불필요).
 * - `addFilters = false`로 Spring Security 필터 우회 (이 테스트에선 controller 로직만 검증).
 * - kakaoLogin 경로는 AuthService를 mock (Kakao 호출/DB 로직은 AuthKakaoIntegrationTest에서 커버).
 * - refresh 경로는 JwtTokenProvider를 mock (foundation에서 완성된 로직이 그대로 유지됨을 확인).
 */
@WebMvcTest(controllers = [AuthController::class])
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class AuthControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockitoBean
    lateinit var authService: AuthService

    @MockitoBean
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    fun `kakaoLogin - 정상 요청이면 AuthService 결과를 래핑하여 200 반환`() {
        Mockito.`when`(authService.loginWithKakao("kakao-access-token-abc")).thenReturn(
            LoginResult(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                userId = 42L,
                isNewUser = true,
            )
        )

        val request = KakaoLoginRequest(kakaoAccessToken = "kakao-access-token-abc")

        mockMvc.perform(
            post("/api/v1/auth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.accessToken").value("access-token"))
            .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
            .andExpect(jsonPath("$.data.userId").value(42))
            .andExpect(jsonPath("$.data.isNewUser").value(true))
    }

    @Test
    fun `kakaoLogin - kakaoAccessToken 공백이면 HTTP 200 + code 700`() {
        val body = mapOf("kakaoAccessToken" to "")

        mockMvc.perform(
            post("/api/v1/auth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
    }

    @Test
    fun `kakaoLogin - AuthService에서 DialogException이면 HTTP 200 + code 701`() {
        Mockito.`when`(authService.loginWithKakao("bad-token"))
            .thenThrow(DialogException("카카오 로그인이 만료되었습니다. 다시 시도해주세요"))

        val request = KakaoLoginRequest(kakaoAccessToken = "bad-token")

        mockMvc.perform(
            post("/api/v1/auth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.DIALOG_ERROR))
    }

    @Test
    fun `refresh - 유효한 refresh token이면 새 access token 발급`() {
        Mockito.`when`(jwtTokenProvider.verifyAndGetUserId("valid-refresh", "refresh")).thenReturn(42L)
        Mockito.`when`(jwtTokenProvider.generateAccessToken(42L)).thenReturn("new-access")

        val request = RefreshRequest(refreshToken = "valid-refresh")

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.accessToken").value("new-access"))
    }

    @Test
    fun `refresh - invalid refresh token이면 HTTP 200 + code 401`() {
        Mockito.`when`(jwtTokenProvider.verifyAndGetUserId("invalid", "refresh")).thenReturn(null)

        val request = RefreshRequest(refreshToken = "invalid")

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))
    }
}
