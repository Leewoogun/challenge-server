package com.lwg.challenge.api.common.exception

import com.lwg.challenge.api.common.BaseResponse
import com.lwg.challenge.api.common.ResponseCode
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 모든 예외를 BaseResponse로 변환하는 전역 핸들러 (ADR-0002).
 *
 * - 비즈니스 예외: HTTP 200 + body.code=7xx (또는 401)
 * - 입력 검증 실패: HTTP 200 + code=700
 * - uncaught: HTTP 500 + code=500 (인프라 장애로 간주)
 *
 * HTTP 4xx는 사용하지 않는다. 비즈니스 예외라도 HTTP 200 + body.code로 구분.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<BaseResponse> {
        log.debug("BusinessException (code={}): {}", e.code, e.message)
        return ResponseEntity.ok(
            BaseResponse(
                error = true,
                code = e.code,
                message = e.message ?: "",
            )
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<BaseResponse> {
        val firstMessage = e.bindingResult.fieldError?.defaultMessage ?: "입력이 올바르지 않습니다"
        log.debug("Validation failed: {}", firstMessage)
        return ResponseEntity.ok(
            BaseResponse(
                error = true,
                code = ResponseCode.SNACKBAR_ERROR,
                message = firstMessage,
            )
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleUncaught(e: Exception): ResponseEntity<BaseResponse> {
        log.error("Uncaught exception", e)
        return ResponseEntity.status(500).body(
            BaseResponse(
                error = true,
                code = ResponseCode.INTERNAL_ERROR,
                message = "서버 오류가 발생했습니다",
            )
        )
    }
}
