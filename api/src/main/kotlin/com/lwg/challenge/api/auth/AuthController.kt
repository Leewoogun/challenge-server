package com.lwg.challenge.api.auth

import com.lwg.challenge.api.auth.dto.KakaoLoginRequest
import com.lwg.challenge.api.auth.dto.LoginResponse
import com.lwg.challenge.api.auth.dto.RefreshData
import com.lwg.challenge.api.auth.dto.RefreshRequest
import com.lwg.challenge.api.auth.dto.RefreshResponse
import com.lwg.challenge.api.common.BaseResponse
import com.lwg.challenge.api.common.exception.UnauthorizedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 인증 엔드포인트.
 *
 * - `/kakao`: Authorization code → 서버가 카카오 토큰 교환 + 사용자 정보 조회 → users upsert → 자체 JWT 발급
 * - `/refresh`: Refresh Token → 새 Access Token (foundation 구현 유지)
 * - `/logout`: Sprint 0 stub 유지 (Sprint 2 `auth-logout`에서 실구현)
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "인증 엔드포인트")
class AuthController(
    private val authService: AuthService,
    private val jwtTokenProvider: JwtTokenProvider,
) {

    @PostMapping("/kakao")
    @Operation(
        summary = "카카오 로그인",
        description = "모바일 WebView가 redirect_uri에서 추출한 authorization code를 받아 " +
            "서버가 /oauth/token + /v2/user/me 호출 후 자체 JWT 발급.",
    )
    fun kakaoLogin(@Valid @RequestBody request: KakaoLoginRequest): LoginResponse {
        val loginData = authService.loginWithKakao(request.code)
        return LoginResponse(data = loginData)
    }

    @PostMapping("/refresh")
    @Operation(
        summary = "Access Token 재발급",
        description = "Refresh Token 검증 후 새 Access Token 발급. Refresh Token 자체는 재발급하지 않는다.",
    )
    fun refresh(@Valid @RequestBody request: RefreshRequest): RefreshResponse {
        val userId = jwtTokenProvider.verifyAndGetUserId(
            request.refreshToken,
            expectedType = JwtTokenProvider.TOKEN_TYPE_REFRESH,
        ) ?: throw UnauthorizedException("Refresh Token이 유효하지 않거나 만료되었습니다")

        val newAccessToken = jwtTokenProvider.generateAccessToken(userId)
        return RefreshResponse(data = RefreshData(accessToken = newAccessToken))
    }

    @DeleteMapping("/logout")
    @Operation(
        summary = "로그아웃",
        description = "Sprint 0: stub — BaseResponse 성공만 반환. Sprint 2+에서 Refresh blacklist + FCM 토큰 제거.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun logout(): BaseResponse {
        return BaseResponse()
    }
}
