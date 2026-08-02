package com.lwg.challenge.service.auth

/**
 * [AuthService.issueTokens] 결과 — access + refresh 한 쌍.
 *
 * 카카오 로그인과 개발용 테스트 로그인이 **같은 발급 경로**를 쓰도록 하기 위해 뽑아낸 타입이다.
 * 발급 로직이 갈라지면 ADR-0009 rotation(`refresh_token_hash` 저장)이 한쪽에서만 동작한다.
 */
data class IssuedTokens(
    val accessToken: String,
    val refreshToken: String,
)
