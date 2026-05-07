package com.lwg.challenge.domain.auth

/**
 * Kakao 사용자 정보 조회 포트.
 *
 * 구현체는 `:infra:external` 의 `KakaoAuthAdapter` (또는 `KakaoOAuthClient`) 가 담당한다.
 * 도메인/서비스 계층은 이 인터페이스 + [KakaoUserInfo] 만 보고 카카오 API raw shape 을 모른다.
 *
 * @throws KakaoTokenInvalidException 토큰이 만료/유효하지 않음 (401/403)
 * @throws KakaoServerException 5xx / 타임아웃 / 네트워크 오류 (구현체 내부에서 1회 재시도 후에도 실패)
 */
interface KakaoAuthPort {
    fun getUserInfo(accessToken: String): KakaoUserInfo
}
