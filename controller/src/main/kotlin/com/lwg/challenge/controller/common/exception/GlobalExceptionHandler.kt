package com.lwg.challenge.controller.common.exception

import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.BusinessException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
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

    /**
     * 요청 본문을 읽지 못한 경우 — 깨진 JSON, enum 값 오타, **날짜 포맷 불일치** 등.
     *
     * 이 핸들러가 없으면 [handleUncaught] 로 떨어져 **HTTP 500 + code 500** 이 나간다.
     * ADR-0002 규약상 클라이언트 입력 문제는 비즈니스 에러(HTTP 200 + code 700)여야 한다.
     *
     * 2026-07-31 challenge-create #7 통합 검증에서 실측으로 발견했다:
     * ```
     * {"opponentId":}              → HTTP 500 / code 500
     * {"deadlineType":"YESTERDAY"} → HTTP 500 / code 500
     * ```
     *
     * **ADR-0010 에서 특히 중요해졌다** — 요청 본문에 `"yyyy-MM-dd HH:mm:ss"` 문자열이 들어오기
     * 시작하면 포맷이 틀린 날짜가 정확히 이 경로를 탄다. 500 으로 나가면 모바일이 비즈니스 에러로
     * 처리하지 못하고 사용자는 "서버 오류가 발생했습니다" 만 보게 된다.
     *
     * 원인 메시지는 Jackson 내부 문구(영문 + 클래스명)라 **사용자에게 노출하지 않는다** —
     * 모바일이 `message` 를 가공 없이 화면에 띄우기 때문이다 (challenge-create 계약 "공통 규약").
     * 진단 정보는 로그로만 남긴다.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(e: HttpMessageNotReadableException): ResponseEntity<BaseResponse> {
        log.warn("요청 본문 파싱 실패: {}", e.mostSpecificCause.message)
        return ResponseEntity.ok(
            BaseResponse(
                error = true,
                code = ResponseCode.SNACKBAR_ERROR,
                message = "요청 형식이 올바르지 않습니다",
            )
        )
    }

    /**
     * 매핑된 핸들러가 없는 경로 — **HTTP 404**.
     *
     * 이 핸들러가 없으면 [handleUncaught] 의 catch-all 이 삼켜 **HTTP 500** 이 나간다.
     * **미등록 경로가 500 을 주는 건 그 자체로 틀렸다** — 500 은 "서버가 고장났다"는 뜻인데
     * 실제로는 "그런 경로가 없다"이기 때문이다.
     *
     * 2026-07-31 실측: `/actuator/health` 가 정확히 이 상태였다 (permitAll 인데 actuator 의존성이
     * 없어 핸들러 부재 → 500).
     *
     * **`dev-test-login` 에서 결정적이다** — 테스트 로그인 엔드포인트는 설정을 켠 환경에서만 빈이
     * 생성되므로(fail-closed), 꺼진 환경에서는 **404 로 "이 서버엔 그 기능이 없다"** 를 알려야 한다.
     * 401 이 나가면 모바일이 "세션 만료"라는 **틀린 결론**을 내린다(SecurityConfig 주석 참조).
     *
     * body 는 프로젝트 규약대로 [BaseResponse] 형태로 주되 **HTTP status 는 404** 로 둔다 —
     * 라우팅 부재는 비즈니스 에러(HTTP 200 + code 7xx)가 아니라 전송 계층의 사실이다.
     *
     * ⚠️ **모바일은 이 body 의 `message` 를 쓰지 않는다.** Ktor 가 `response.status.description`
     * (영문 `"Not Found"`)으로 채우기 때문이다. 여기 한국어를 넣어도 화면에는 안 나오며,
     * 모바일이 자체 문구로 치환한다 (dev-test-login api-contract 명시). 이 body 는 로그·디버깅용이다.
     */
    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun handleNotFound(e: Exception): ResponseEntity<BaseResponse> {
        log.debug("매핑된 핸들러 없음: {}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            BaseResponse(
                error = true,
                code = HttpStatus.NOT_FOUND.value(),
                message = "요청한 경로를 찾을 수 없습니다",
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
