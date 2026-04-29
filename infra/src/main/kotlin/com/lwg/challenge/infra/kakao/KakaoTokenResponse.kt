package com.lwg.challenge.infra.kakao

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Kakao `/oauth/token` 응답 중 우리가 사용하는 필드만.
 *
 * Kakao 원문 shape:
 * ```
 * {
 *   "token_type": "bearer",
 *   "access_token": "...",
 *   "expires_in": 21599,
 *   "refresh_token": "...",
 *   "refresh_token_expires_in": 5183999,
 *   "scope": "profile_nickname"
 * }
 * ```
 *
 * Kakao refresh_token은 보관하지 않는다 — 자체 JWT refresh 토큰을 발급해 모바일에 돌려주고,
 * 카카오 토큰은 access_token으로 `/v2/user/me` 한 번 호출하는 데에만 쓴다 (ADR-0008).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KakaoTokenResponse(
    @JsonProperty("access_token")
    val accessToken: String,

    @JsonProperty("token_type")
    val tokenType: String? = null,

    @JsonProperty("expires_in")
    val expiresIn: Long? = null,

    @JsonProperty("refresh_token")
    val refreshToken: String? = null,

    @JsonProperty("refresh_token_expires_in")
    val refreshTokenExpiresIn: Long? = null,

    @JsonProperty("scope")
    val scope: String? = null,
)
