package com.lwg.challenge.api.common

/**
 * API 응답 베이스 클래스. CarOwnerRenew 프로젝트 패턴 이식.
 *
 * - 성공: error=false, code=200
 * - 비즈니스 에러: error=true, code=7xx (HTTP는 항상 200)
 * - 토큰 만료: error=true, code=401 (HTTP 200)
 * - 인프라 장애: error=true, code=500 (HTTP 500)
 *
 * 데이터 응답은 data class가 이 클래스를 상속하여 `data: T` 필드 추가:
 * ```
 * data class LoginResponse(val data: LoginData) : BaseResponse()
 * ```
 */
open class BaseResponse(
    val error: Boolean = false,
    val code: Int = 200,
    val message: String = "",
)

/**
 * 응답 코드 상수. 모바일 `:remote:datasource`의 `ApiCode`와 일치시킬 것.
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
