package com.lwg.challenge.domain.auth

/**
 * Kakao `/v2/user/me` 응답을 도메인 친화적으로 표현한 값 객체.
 * `:service` 는 이 타입만 보고 카카오 API 의 raw shape 을 알 필요가 없다.
 */
data class KakaoUserInfo(
    val kakaoId: Long,
    val nickname: String?,
    val profileImageUrl: String?,
    /** Kakao 가 알려준 raw 전화번호 (정규화/해시 전). 동의 거부 또는 미존재 시 null. */
    val rawPhoneNumber: String?,
)
