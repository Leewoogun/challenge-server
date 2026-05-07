package com.lwg.challenge.api.auth.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 카카오 로그인 요청.
 *
 * 모바일이 Kakao SDK로 직접 획득한 access_token을 그대로 전달한다.
 * 서버는 이 토큰으로 `GET /v2/user/me` 만 호출 (코드 → 토큰 교환 단계 없음).
 * SDK 방식이라 `client_secret` / `redirect_uri` 등 OAuth 파라미터는 서버에 보관하지 않는다.
 */
@Schema(description = "카카오 로그인 요청 (Kakao SDK access_token 전달)")
data class KakaoLoginRequest(
    @field:NotBlank(message = "카카오 토큰이 누락되었습니다")
    @field:Size(max = 2048, message = "카카오 토큰 길이가 비정상입니다")
    @field:Schema(
        description = "Kakao SDK가 모바일에서 획득한 access_token",
        example = "eyJ0eXAiOiJKV1QiLCJhbGciOi...",
    )
    val kakaoAccessToken: String,
)
