package com.lwg.challenge.controller.user

import com.lwg.challenge.controller.user.dto.UserInfoData
import com.lwg.challenge.controller.user.dto.UserInfoResponse
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.service.user.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 사용자 컨트롤러 (spec-user-info §4.4, api-contract-user-info §1).
 *
 * Bearer JWT 필요. 인증은 SecurityFilterChain `.anyRequest().authenticated()` + JwtAuthenticationFilter.
 * 정상 케이스 principal 은 userId(Long). currentUserId() 헬퍼는 FriendController 와 동일.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "User", description = "본인 정보 조회")
class UserController(
    private val userService: UserService,
) {

    @GetMapping("/me")
    @Operation(
        summary = "본인 정보 조회",
        description = "Bearer 토큰의 userId 로 id, kakaoId, nickname, profileImageUrl 반환.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun getMe(): UserInfoResponse {
        val me = currentUserId()
        val user = userService.getMe(me)
        return UserInfoResponse(
            data = UserInfoData(
                id = user.id ?: error("영속 user 의 id 가 null 일 수 없습니다"),
                kakaoId = user.kakaoId,
                nickname = user.nickname,
                profileImageUrl = user.profileImageUrl,
            ),
        )
    }

    private fun currentUserId(): Long {
        val auth = SecurityContextHolder.getContext().authentication
            ?: throw UnauthorizedException("인증이 필요합니다")
        val principal = auth.principal
        return when (principal) {
            is Long -> principal
            is Number -> principal.toLong()
            is String -> principal.toLongOrNull()
                ?: throw UnauthorizedException("인증 정보가 올바르지 않습니다")
            else -> throw UnauthorizedException("인증 정보가 올바르지 않습니다")
        }
    }
}
