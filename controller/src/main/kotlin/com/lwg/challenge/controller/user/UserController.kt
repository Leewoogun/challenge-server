package com.lwg.challenge.controller.user

import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.controller.user.dto.FcmTokenRequest
import com.lwg.challenge.controller.user.dto.UserInfoData
import com.lwg.challenge.controller.user.dto.UserInfoResponse
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.service.user.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 사용자 컨트롤러 (spec-user-info §4.4, api-contract-user-info §1 / push-fcm §1).
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

    /**
     * FCM 토큰 등록·갱신 (push-fcm T-B1, 계약 §1).
     *
     * 응답에 `data` 가 없다 — 서버가 돌려줄 것이 없어 [BaseResponse] 3필드(`error`/`code`/`message`)만
     * 나간다. ⚠️ **모바일은 `data` 키가 온다고 가정하면 안 된다.** 없는 키를 non-nullable 로 선언하면
     * kotlinx.serialization 이 `MissingFieldException` 을 던진다 (#25 감사에서 정리한 실패 모드).
     * `logout` 과 동일한 형태다.
     *
     * PUT 인 이유: 같은 토큰을 여러 번 보내도 결과가 같은 **멱등** 연산이고, 모바일이 로그인 직후와
     * `onNewToken` 양쪽에서 부르므로 중복 호출이 정상 경로이기 때문이다.
     */
    @PutMapping("/me/fcm-token")
    @Operation(
        summary = "FCM 토큰 등록·갱신",
        description = "같은 토큰을 쥔 다른 계정의 토큰을 먼저 해제한 뒤 본인에게 등록한다 (한 기기 = 한 계정). " +
            "멱등하며 응답 body 에 data 가 없다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun updateFcmToken(@Valid @RequestBody request: FcmTokenRequest): BaseResponse {
        userService.updateFcmToken(currentUserId(), request.fcmToken)
        return BaseResponse()
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
