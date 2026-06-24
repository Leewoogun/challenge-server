package com.lwg.challenge.domain.common.exception

import com.lwg.challenge.domain.common.ResponseCode

/**
 * 비즈니스 예외. throw 되면 `:controller` 의 GlobalExceptionHandler 가 잡아 변환한다.
 * - HTTP 표준 범위 코드(400~599)는 해당 HTTP status 로 응답 (예: UnauthorizedException 401 → HTTP 401).
 * - 700번대 비즈니스 코드(SNACKBAR/DIALOG/FULL_SCREEN 등)는 HTTP 200 + body.code 로 응답.
 */
abstract class BusinessException(
    val code: Int,
    message: String,
) : RuntimeException(message)

/** code=700. 모바일에서 스낵바/토스트로 표시. 가장 흔한 비즈니스 에러. */
class SnackbarException(message: String) :
    BusinessException(ResponseCode.SNACKBAR_ERROR, message)

/** code=701. 모바일에서 확인 다이얼로그로 표시. */
class DialogException(message: String) :
    BusinessException(ResponseCode.DIALOG_ERROR, message)

/** code=702 또는 703. 모바일에서 전체 화면 에러로 표시. code를 명시적으로 지정해야 함. */
class FullScreenException(code: Int, message: String) :
    BusinessException(code, message) {
    init {
        require(code == ResponseCode.FULL_SCREEN_ERROR_A || code == ResponseCode.FULL_SCREEN_ERROR_B) {
            "FullScreenException code must be ${ResponseCode.FULL_SCREEN_ERROR_A} or ${ResponseCode.FULL_SCREEN_ERROR_B}"
        }
    }
}

/** code=705. 모바일에서 단일 버튼 다이얼로그로 표시. */
class OneButtonDialogException(message: String) :
    BusinessException(ResponseCode.ONE_BUTTON_DIALOG_ERROR, message)

/** code=401. 토큰 만료 — 모바일은 refresh 호출 후 재시도. */
class UnauthorizedException(message: String = "인증이 필요합니다") :
    BusinessException(ResponseCode.UNAUTHORIZED, message)
