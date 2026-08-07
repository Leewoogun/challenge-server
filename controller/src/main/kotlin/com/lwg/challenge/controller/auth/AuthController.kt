package com.lwg.challenge.controller.auth

import com.lwg.challenge.controller.auth.dto.KakaoLoginRequest
import com.lwg.challenge.controller.auth.dto.LoginData
import com.lwg.challenge.controller.auth.dto.LoginResponse
import com.lwg.challenge.controller.auth.dto.RefreshData
import com.lwg.challenge.controller.auth.dto.RefreshRequest
import com.lwg.challenge.controller.auth.dto.RefreshResponse
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.service.auth.AuthService
import com.lwg.challenge.service.auth.LoginResult
import com.lwg.challenge.service.auth.RefreshResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.context.SecurityContextHolder
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
        summary = "Access Token 재발급 (Rotation)",
        description = "Refresh Token 검증 후 새 access + 새 refresh 를 함께 발급한다. 이전 refresh 는 즉시 무효화된다.",
    )
    fun refresh(@Valid @RequestBody request: RefreshRequest): RefreshResponse {
        val result: RefreshResult = authService.refresh(request.refreshToken)
        return RefreshResponse(
            data = RefreshData(
                accessToken = result.accessToken,
                refreshToken = result.refreshToken,
            ),
        )
    }

    /**
     * 로그아웃 (push-fcm T-B2, 계약 §2). Sprint 0 스텁을 실구현으로 채운 것이며 **응답 shape 은 그대로**다.
     *
     * `refresh_token_hash` 와 `fcm_token` 을 함께 NULL 로 만든다. 멱등하므로 이미 로그아웃된
     * 상태에서 다시 불러도 200 이다 (상세 근거는 `AuthService.logout` KDoc).
     *
     * 인증이 필요하다 — SecurityConfig 의 permitAll 목록에 `/logout` 은 **없고**
     * `.anyRequest().authenticated()` 에 걸린다. 즉 여기 도달한 시점에 principal 은 항상 존재한다.
     */
    @DeleteMapping("/logout")
    @Operation(
        summary = "로그아웃",
        description = "refresh_token_hash 와 fcm_token 을 NULL 로 만든다. 멱등하며 응답 body 에 data 가 없다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun logout(): BaseResponse {
        authService.logout(currentUserId())
        return BaseResponse()
    }

    /** principal 에서 userId 추출. 다른 컨트롤러(UserController/FriendController)와 동일한 헬퍼다. */
    private fun currentUserId(): Long {
        val auth = SecurityContextHolder.getContext().authentication
            ?: throw UnauthorizedException("인증이 필요합니다")
        return when (val principal = auth.principal) {
            is Long -> principal
            is Number -> principal.toLong()
            is String -> principal.toLongOrNull()
                ?: throw UnauthorizedException("인증 정보가 올바르지 않습니다")
            else -> throw UnauthorizedException("인증 정보가 올바르지 않습니다")
        }
    }
}
