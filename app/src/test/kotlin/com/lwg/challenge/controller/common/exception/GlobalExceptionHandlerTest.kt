package com.lwg.challenge.controller.common.exception

import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.DialogException
import com.lwg.challenge.domain.common.exception.OneButtonDialogException
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * GlobalExceptionHandler 단위 테스트. Spring context 없이 핸들러 객체만 직접 호출하여 매핑 검증.
 */
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

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
