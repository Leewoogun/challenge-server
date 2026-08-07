package com.lwg.challenge.controller.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.controller.auth.dto.KakaoLoginRequest
import com.lwg.challenge.controller.auth.dto.RefreshRequest
import com.lwg.challenge.controller.common.exception.GlobalExceptionHandler
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.DialogException
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.service.auth.AuthService
import com.lwg.challenge.service.auth.LoginResult
import com.lwg.challenge.service.auth.RefreshResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * AuthController 슬라이스 테스트.
 *
 * - `@WebMvcTest` 로 컨트롤러·예외 핸들러만 로드 (DB 불필요).
 * - `addFilters = false`로 Spring Security 필터 우회 (이 테스트에선 controller 로직만 검증).
 * - kakaoLogin 경로는 AuthService를 mock (Kakao 호출/DB 로직은 AuthKakaoIntegrationTest에서 커버).
 * - refresh 경로도 AuthService.refresh()를 mock (rotation 로직 본체는 AuthServiceRefreshTest 후보, ADR-0009).
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
    fun `refresh - 유효한 refresh token이면 새 access + refresh 발급`() {
        Mockito.`when`(authService.refresh("valid-refresh")).thenReturn(
            RefreshResult(accessToken = "new-access", refreshToken = "new-refresh"),
        )

        val request = RefreshRequest(refreshToken = "valid-refresh")

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.accessToken").value("new-access"))
            .andExpect(jsonPath("$.data.refreshToken").value("new-refresh"))
    }

    @Test
    fun `refresh - invalid refresh token이면 HTTP 401 + code 401`() {
        Mockito.`when`(authService.refresh("invalid"))
            .thenThrow(UnauthorizedException("유효하지 않은 refresh token 입니다"))

        val request = RefreshRequest(refreshToken = "invalid")

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))
    }

    // ─── DELETE /logout (push-fcm T-B2 — 스텁 → 실구현) ────────────────────

    /**
     * 🔴 **응답 shape 이 스텁 시절과 동일해야 한다.**
     * `data` 키가 없는 [com.lwg.challenge.controller.common.BaseResponse] 3필드 그대로다
     * (foundation/api-contract.md 에 명문화된 형태). 여기에 `data` 를 끼워 넣는 것 자체는
     * breaking 이 아니지만, 계약 문서가 "shape 무변경"이라고 약속했으므로 고정한다.
     */
    @Test
    fun `logout - 응답은 data 없는 BaseResponse 200 이고 서비스가 호출된다`() {
        withPrincipal(meId)

        val body = mockMvc.perform(delete("/api/v1/auth/logout"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.message").value(""))
            .andReturn().response.contentAsString

        // 🔴 원문 확인. jsonPath 의 doesNotExist 는 `{"data":null}` 에서도 통과하므로
        // "키가 없다" 를 고정하지 못한다 (UserControllerTest 의 동일 주석 참조).
        assertFalse(body.contains("\"data\""), "data 키가 없어야 한다 (계약 §2 shape 무변경). 실제 응답=$body")

        Mockito.verify(authService).logout(meId)
    }

    /** 멱등 — 연속 호출도 매번 200 이다. 클라이언트가 로그아웃을 재시도해도 안전해야 한다. */
    @Test
    fun `logout - 두 번 불러도 200`() {
        withPrincipal(meId)

        repeat(2) {
            mockMvc.perform(delete("/api/v1/auth/logout"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
        }

        Mockito.verify(authService, Mockito.times(2)).logout(meId)
    }

    /**
     * principal 이 없는 상태 — 실제 배포에서는 SecurityFilterChain 이 먼저 막지만
     * (`/logout` 은 permitAll 목록에 없다), 컨트롤러 자체도 열려 있으면 안 된다.
     * 이 테스트는 `addFilters = false` 라 필터가 없는 조건에서 컨트롤러 단독 방어를 본다.
     */
    @Test
    fun `logout - principal 이 없으면 HTTP 401 + code 401`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(delete("/api/v1/auth/logout"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))

        Mockito.verify(authService, Mockito.never()).logout(Mockito.anyLong())
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun withPrincipal(userId: Long) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                userId,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    private companion object {
        const val meId = 42L
    }
}
