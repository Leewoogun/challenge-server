package com.lwg.challenge.infra.kakao

/**
 * Kakao OAuth 호출 결과를 의미별로 구분하는 예외.
 *
 * - [KakaoTokenInvalidException] — 401/403. 사용자 재로그인 필요.
 * - [KakaoServerException] — 5xx / 타임아웃 / 네트워크 오류. 재시도 후 실패.
 *
 * `:api` 계층의 AuthService가 이 예외를 받아 BaseResponse code (701 / 703)로 변환한다.
 */
class KakaoTokenInvalidException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class KakaoServerException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
