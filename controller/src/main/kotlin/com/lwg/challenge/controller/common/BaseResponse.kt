package com.lwg.challenge.controller.common

/**
 * API 응답 베이스 클래스.
 *
 * - 성공: error=false, code=200
 * - 비즈니스 에러: error=true, code=7xx (HTTP 는 항상 200)
 * - 토큰 만료: error=true, code=401 (HTTP 200)
 * - 인프라 장애: error=true, code=500 (HTTP 500)
 */
open class BaseResponse(
    val error: Boolean = false,
    val code: Int = 200,
    val message: String = "",
)
