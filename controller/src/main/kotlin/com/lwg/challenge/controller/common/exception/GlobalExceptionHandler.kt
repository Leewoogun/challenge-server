package com.lwg.challenge.controller.common.exception

import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.BusinessException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * 응답 규약:
     * - HTTP 표준 범위 코드(400~599) → HTTP status 그대로 (예: UnauthorizedException 401 → HTTP 401).
     *   모바일 Ktor Authenticator 등 HTTP status 기반 미들웨어가 동작해야 함.
     * - 700번대 비즈니스 코드(스낵바/다이얼로그/전체화면) → HTTP 200 + body.code.
     *   UI 분류 코드이지 전송 실패가 아니므로 미들웨어가 끼어들지 않도록 200 으로 통일.
     */
    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<BaseResponse> {
        log.debug("BusinessException (code={}): {}", e.code, e.message)
        val status = if (e.code in 400..599) HttpStatus.valueOf(e.code) else HttpStatus.OK
        return ResponseEntity.status(status).body(
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
