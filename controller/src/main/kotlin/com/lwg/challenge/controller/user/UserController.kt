package com.lwg.challenge.controller.user

import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.controller.user.dto.FcmTokenRequest
import com.lwg.challenge.controller.user.dto.UserInfoData
import com.lwg.challenge.controller.user.dto.UserInfoResponse
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.service.user.UserService
import com.lwg.challenge.service.user.WithdrawalService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
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
@Tag(name = "User", description = "본인 정보 조회 / 회원탈퇴")
class UserController(
    private val userService: UserService,
    private val withdrawalService: WithdrawalService,
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
                // kakaoId 가 null 인 것은 탈퇴 계정뿐이고(mypage T-B2), [UserService.getMe] 가
                // 그 경우 401 을 던지므로 여기 도달하면 non-null 이다. id 와 같은 성격의 단언이다.
                kakaoId = user.kakaoId ?: error("탈퇴하지 않은 user 의 kakaoId 가 null 일 수 없습니다"),
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

    /**
     * 회원탈퇴 (mypage T-B2).
     *
     * 🔴 응답에 `data` 가 없다 — `logout` / `fcm-token` 등록과 **같은 shape** 이다. 서버가 돌려줄
     * 것이 없어 [BaseResponse] 3필드(`error`/`code`/`message`)만 나간다. ⚠️ 모바일은 `data` 키가
     * 온다고 가정하면 안 된다 (없는 키를 non-nullable 로 선언하면 `MissingFieldException`).
     *
     * **멱등하다** — 이미 탈퇴한 계정이 다시 불러도 200 이다. access token 이 최대 1시간 살아
     * 있어 재요청이 실제로 가능하다 (`WithdrawalService` KDoc).
     *
     * ⚠️ **실패가 200 + code=701 로 나갈 수 있다.** 카카오 연결 해제가 이행되지 않으면
     * `DialogException` 이 올라온다 — 탈퇴는 로그아웃과 달리 *"언제나 성공"* 이어서는 안 된다.
     * 앱은 code 를 보고 성공/실패를 갈라야 하며 HTTP status 만 봐서는 안 된다.
     *
     * 인증이 필요하다 — SecurityConfig 의 permitAll 목록에 없고 `.anyRequest().authenticated()`
     * 에 걸리므로, 여기 도달한 시점에 principal 은 항상 존재한다.
     */
    @DeleteMapping("/me")
    @Operation(
        summary = "회원탈퇴",
        description = "카카오 연결을 끊고 users 개인정보를 익명화한다(row 는 보존). friendships 는 삭제되고 " +
            "인증 사진 파일과 key 는 지워지지만 챌린지·계약서·결과·전적은 그대로 남는다. " +
            "멱등하며 응답 body 에 data 가 없다. 카카오 연결 해제 실패 시 code=701.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun withdraw(): BaseResponse {
        withdrawalService.withdraw(currentUserId())
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
