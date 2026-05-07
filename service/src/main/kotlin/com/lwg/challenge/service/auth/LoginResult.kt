package com.lwg.challenge.service.auth

/**
 * AuthService.loginWithKakao() 의 결과. Controller 가 LoginData (HTTP 응답 DTO) 로 변환한다.
 */
data class LoginResult(
    val accessToken: String,
    val refreshToken: String,
    val userId: Long,
    val isNewUser: Boolean,
)
