package com.lwg.challenge.domain.common

/**
 * 응답 코드 상수. 모바일 `:remote:datasource` 의 `ApiCode` 와 일치시킬 것.
 *
 * BaseResponse 와는 별개로 도메인에 두는 이유: BusinessException 들이 이 코드 상수를 참조하므로
 * 도메인/서비스 계층에서 import 할 수 있어야 한다.
 */
object ResponseCode {
    const val SUCCESS = 200
    const val UNAUTHORIZED = 401
    const val INTERNAL_ERROR = 500

    const val SNACKBAR_ERROR = 700
    const val DIALOG_ERROR = 701
    const val FULL_SCREEN_ERROR_A = 702
    const val FULL_SCREEN_ERROR_B = 703
    const val ONE_BUTTON_DIALOG_ERROR = 705
}
