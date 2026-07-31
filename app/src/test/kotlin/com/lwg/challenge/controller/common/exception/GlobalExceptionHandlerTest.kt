package com.lwg.challenge.controller.common.exception

import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.DialogException
import com.lwg.challenge.domain.common.exception.OneButtonDialogException
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * GlobalExceptionHandler 단위 테스트. Spring context 없이 핸들러 객체만 직접 호출하여 매핑 검증.
 */
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    private fun notReadable(reason: String) = HttpMessageNotReadableException(
        reason,
        MockHttpInputMessage(ByteArray(0)),
    )

    /**
     * 2026-07-31 challenge-create #7 통합 검증에서 실서버로 발견한 회귀 방지.
     *
     * 핸들러가 없던 시절엔 `handleUncaught` 로 떨어져 **HTTP 500 + code 500** 이 나갔다:
     * ```
     * {"opponentId":}              → HTTP 500 / code 500
     * {"deadlineType":"YESTERDAY"} → HTTP 500 / code 500
     * ```
     * ADR-0010 이후 요청 본문에 날짜 문자열이 들어오면 포맷 오류가 정확히 이 경로를 타므로,
     * 500 이 아니라 비즈니스 에러(700)로 나가야 모바일이 사용자에게 제대로 안내할 수 있다.
     */
    @Test
    fun `요청 본문 파싱 실패는 HTTP 200 + code 700 이다`() {
        val response = handler.handleNotReadable(notReadable("JSON parse error: Unexpected character"))

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertEquals(true, body.error)
        assertEquals(ResponseCode.SNACKBAR_ERROR, body.code)
    }

    @Test
    fun `파싱 실패 메시지에 Jackson 내부 문구가 노출되지 않는다`() {
        // 모바일이 message 를 가공 없이 화면에 띄우므로 영문 예외 문구가 새면 사용자에게 그대로 보인다.
        val response = handler.handleNotReadable(
            notReadable("JSON parse error: Cannot deserialize value of type `java.time.LocalDateTime`"),
        )

        val message = response.body!!.message
        assertEquals("요청 형식이 올바르지 않습니다", message)
        assertEquals(false, message.contains("JSON"))
        assertEquals(false, message.contains("java.time"))
    }

    @Test
    fun `SnackbarException은 HTTP 200 + code 700로 변환된다`() {
        val response = handler.handleBusiness(SnackbarException("이미 가입된 사용자입니다"))

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertEquals(true, body.error)
        assertEquals(ResponseCode.SNACKBAR_ERROR, body.code)
        assertEquals("이미 가입된 사용자입니다", body.message)
    }

    @Test
    fun `DialogException은 code 701`() {
        val response = handler.handleBusiness(DialogException("세션이 만료되었습니다"))

        assertEquals(200, response.statusCode.value())
        assertEquals(ResponseCode.DIALOG_ERROR, response.body!!.code)
    }

    @Test
    fun `UnauthorizedException은 HTTP 401 + code 401`() {
        val response = handler.handleBusiness(UnauthorizedException())

        assertEquals(401, response.statusCode.value())
        assertEquals(ResponseCode.UNAUTHORIZED, response.body!!.code)
    }

    @Test
    fun `OneButtonDialogException은 code 705`() {
        val response = handler.handleBusiness(OneButtonDialogException("계약서가 이미 확정되었습니다"))

        assertEquals(200, response.statusCode.value())
        assertEquals(ResponseCode.ONE_BUTTON_DIALOG_ERROR, response.body!!.code)
    }

    @Test
    fun `uncaught Exception은 HTTP 500 + code 500`() {
        val response = handler.handleUncaught(RuntimeException("unexpected"))

        assertEquals(500, response.statusCode.value())
        val body = response.body!!
        assertEquals(true, body.error)
        assertEquals(ResponseCode.INTERNAL_ERROR, body.code)
    }
}
