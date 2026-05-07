package com.lwg.challenge.controller.auth

import com.lwg.challenge.controller.auth.dto.KakaoLoginRequest
import com.lwg.challenge.controller.auth.dto.LoginData
import com.lwg.challenge.controller.auth.dto.LoginResponse
import com.lwg.challenge.controller.auth.dto.RefreshData
import com.lwg.challenge.controller.auth.dto.RefreshRequest
import com.lwg.challenge.controller.auth.dto.RefreshResponse
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.core.auth.JwtTokenProvider
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.service.auth.AuthService
import com.lwg.challenge.service.auth.LoginResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
        description = "모바일이 Kakao SDK 로 획득한 access_token 을 받아 서버가 /v2/user/me 호출 후 자체 JWT 발급.",
    )
    fun kakaoLogin(@Valid @RequestBody request: KakaoLoginRequest): LoginResponse {
        val result: LoginResult = authService.loginWithKakao(request.kakaoAccessToken)
        return LoginResponse(
            data = LoginData(
                accessToken = result.accessToken,
                refreshToken = result.refreshToken,
                userId = result.userId,
                isNewUser = result.isNewUser,
            ),
        )
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
        ) ?: throw UnauthorizedException("Refresh Token 이 유효하지 않거나 만료되었습니다")

        val newAccessToken = jwtTokenProvider.generateAccessToken(userId)
        return RefreshResponse(data = RefreshData(accessToken = newAccessToken))
    }

    @DeleteMapping("/logout")
    @Operation(
        summary = "로그아웃",
        description = "Sprint 0: stub — BaseResponse 성공만 반환. Sprint 2+ 에서 Refresh blacklist + FCM 토큰 제거.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun logout(): BaseResponse {
        return BaseResponse()
    }
}
