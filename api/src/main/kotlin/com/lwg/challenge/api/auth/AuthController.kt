package com.lwg.challenge.api.auth

import com.lwg.challenge.api.auth.dto.KakaoLoginRequest
import com.lwg.challenge.api.auth.dto.LoginData
import com.lwg.challenge.api.auth.dto.LoginResponse
import com.lwg.challenge.api.auth.dto.RefreshData
import com.lwg.challenge.api.auth.dto.RefreshRequest
import com.lwg.challenge.api.auth.dto.RefreshResponse
import com.lwg.challenge.api.common.BaseResponse
import com.lwg.challenge.api.common.exception.SnackbarException
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
 * 인증 엔드포인트 (Sprint 0 foundation).
 *
 * TODO Sprint 1 `auth-kakao`:
 *  - kakaoLogin에서 Kakao `/v2/user/me` API로 access_token 실제 검증
 *  - users 테이블 upsert + phone_number SHA-256 해시 저장 (ADR-0008)
 *  - logout에서 Refresh Token blacklist + fcm_token null 처리
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "인증 엔드포인트")
class AuthController(
    private val jwtTokenProvider: JwtTokenProvider,
) {

    @PostMapping("/kakao")
    @Operation(
        summary = "카카오 로그인",
        description = "Kakao access_token으로 JWT 발급. Sprint 0: stub — 토큰 비어있지 않으면 임의 userId=1로 JWT 발급.",
    )
    fun kakaoLogin(@Valid @RequestBody request: KakaoLoginRequest): LoginResponse {
        // TODO Sprint 1: Kakao /v2/user/me로 kakaoAccessToken 검증 + kakao_account.phone_number 대조 + users upsert
        if (request.kakaoAccessToken.isBlank()) {
            throw SnackbarException("카카오 토큰이 유효하지 않습니다")
        }

        val stubUserId = 1L
        val accessToken = jwtTokenProvider.generateAccessToken(stubUserId)
        val refreshToken = jwtTokenProvider.generateRefreshToken(stubUserId)

        return LoginResponse(
            data = LoginData(
                accessToken = accessToken,
                refreshToken = refreshToken,
                userId = stubUserId,
                isNewUser = false,
            )
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
        ) ?: throw UnauthorizedException("Refresh Token이 유효하지 않거나 만료되었습니다")

        val newAccessToken = jwtTokenProvider.generateAccessToken(userId)
        return RefreshResponse(data = RefreshData(accessToken = newAccessToken))
    }

    @DeleteMapping("/logout")
    @Operation(
        summary = "로그아웃",
        description = "Sprint 0: stub — BaseResponse 성공만 반환. Sprint 1+에서 Refresh blacklist + FCM 토큰 제거.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun logout(): BaseResponse {
        // TODO Sprint 1+: Refresh Token blacklist (Redis) + users.fcm_token = null
        return BaseResponse()
    }
}
