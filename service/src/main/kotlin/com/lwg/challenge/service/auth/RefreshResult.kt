package com.lwg.challenge.service.auth

/**
 * AuthService.refresh() 의 결과. Controller 가 RefreshData (HTTP 응답 DTO) 로 변환한다.
 *
 * rotation 후 새로 발급된 access + refresh 쌍.
 */
data class RefreshResult(
    val accessToken: String,
    val refreshToken: String,
)
