package com.lwg.challenge.controller.common.exception

import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.DialogException
import com.lwg.challenge.domain.common.exception.OneButtonDialogException
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver
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

    /**
     * dev-test-login T-B1b. 매핑된 핸들러가 없으면 **404** — 500 이 아니다.
     *
     * 이 핸들러가 없던 시절 `/actuator/health` 가 catch-all 에 걸려 500 을 냈다(2026-07-31 실측).
     * `dev-test-login` 은 꺼진 환경에서 **404 로 "그 기능이 없다"** 를 알려야 하는데,
     * 500 이나 401 이 나가면 모바일이 각각 "서버 고장" / "세션 만료" 라는 **틀린 결론**을 낸다.
     */
    @Test
    fun `매핑된 핸들러가 없으면 HTTP 404 다`() {
        val response = handler.handleNotFound(
            NoResourceFoundException(HttpMethod.POST, "/api/v1/auth/test-login"),
        )

        assertEquals(404, response.statusCode.value())
        val body = response.body!!
        assertEquals(true, body.error)
        assertEquals(404, body.code)
    }

    /**
     * 인증 사진 제출(multipart) 계열. 셋 다 **컨트롤러 진입 전에** 서블릿/바인딩 계층이 던지므로
     * 서비스 코드로는 잡을 수 없다 — 이 핸들러들이 없으면 전부 `handleUncaught` 의 500 이 된다.
     * 클라이언트 입력 문제이므로 ADR-0002 규약상 비즈니스 에러(HTTP 200 + code 700)여야 한다.
     */
    /**
     * 🔴 상위 타입 핸들러(`handleMultipart`, `MultipartException`)가 생겨도
     * 하위 타입인 `MaxUploadSizeExceededException` 은 여전히 전용 핸들러의 용량 문구로 나간다는
     * 것을 고정한다 — Spring 은 더 구체적인 핸들러를 고르므로 두 핸들러의 순서에 의존하지 않는다.
     * 이 테스트가 깨지면 용량 초과가 "요청 형식이 올바르지 않습니다" 로 뭉개진 것이다.
     */
    @Test
    fun `multipart 용량 초과는 HTTP 200 + code 700 이다`() {
        val response = handler.handleMaxUploadSize(MaxUploadSizeExceededException(5_242_880L))

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertEquals(true, body.error)
        assertEquals(ResponseCode.SNACKBAR_ERROR, body.code)
        assertEquals("사진 용량이 너무 커요 (최대 5MB)", body.message)
    }

    @Test
    fun `multipart part 누락은 HTTP 200 + code 700 이다`() {
        val response = handler.handleMissingPart(MissingServletRequestPartException("photo"))

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertEquals(true, body.error)
        assertEquals(ResponseCode.SNACKBAR_ERROR, body.code)
        assertEquals("사진을 첨부해주세요", body.message)
    }

    /**
     * multipart 파싱 실패 — **boundary 파라미터 누락**이 대표적이다. 클라이언트가 전역
     * `Content-Type: application/json` 으로 boundary 를 지워버리면 (mobile-dev 실측: Ktor
     * DefaultRequest 가 body 의 contentType 을 이긴다) Tomcat 의 InvalidContentTypeException 이
     * `MultipartException` 으로 감싸져 나온다. 이 핸들러가 없으면 `handleUncaught` 의
     * HTTP 500 + code 500 이 된다.
     */
    @Test
    fun `multipart 파싱 실패는 HTTP 200 + code 700 이다`() {
        val response = handler.handleMultipart(
            MultipartException("Failed to parse multipart servlet request"),
        )

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertEquals(true, body.error)
        assertEquals(ResponseCode.SNACKBAR_ERROR, body.code)
        assertEquals("요청 형식이 올바르지 않습니다", body.message)
    }

    /**
     * 🔴 **상속 관계 때문에 후보 핸들러가 2개인 자리** — `MaxUploadSizeExceededException` 은
     * `MultipartException` 의 하위 타입이라 `handleMaxUploadSize` 와 `handleMultipart` 둘 다 후보다.
     *
     * 위의 핸들러 직접 호출 테스트들은 "각 핸들러가 무엇을 반환하는가"만 검증할 뿐,
     * **Spring 이 어느 핸들러를 고르는가(디스패치)는 검증하지 못한다.** 이 테스트는
     * [ExceptionHandlerMethodResolver] — 실제 런타임 디스패치가 쓰는 바로 그 해석기 — 로
     * 그 선택 자체를 고정한다: 용량 초과는 더 구체적인 전용 핸들러로 가야 하고, 선언 순서에
     * 의존하지 않는다.
     *
     * 상위 타입 핸들러를 나중에 추가한 사람이 용량 문구("사진 용량이 너무 커요 (최대 5MB)")를
     * **조용히 "요청 형식이 올바르지 않습니다" 로 가려 버리는 회귀**가 여기서 잡힌다.
     */
    @Test
    fun `용량 초과 디스패치는 handleMultipart 가 아니라 용량 전용 핸들러를 고른다`() {
        val resolver = ExceptionHandlerMethodResolver(GlobalExceptionHandler::class.java)

        val maxUploadTarget = resolver.resolveMethod(MaxUploadSizeExceededException(5_242_880L))?.name
        val multipartTarget = resolver.resolveMethod(MultipartException("Failed to parse multipart servlet request"))?.name

        assertEquals("handleMaxUploadSize", maxUploadTarget)
        assertEquals("handleMultipart", multipartTarget)
    }

    /**
     * 개정 전 계약(JSON `{photoKey}`)으로 제출하는 구버전 앱이 전환 기간 동안 실제로 이 경로를
     * 탄다 — `consumes = multipart/form-data` 라 JSON 요청은 컨트롤러에 도달하지 못한다.
     */
    @Test
    fun `지원하지 않는 Content-Type 은 HTTP 200 + code 700 이다`() {
        val response = handler.handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException(MediaType.APPLICATION_JSON, listOf(MediaType.MULTIPART_FORM_DATA)),
        )

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertEquals(true, body.error)
        assertEquals(ResponseCode.SNACKBAR_ERROR, body.code)
        assertEquals("요청 형식이 올바르지 않습니다", body.message)
    }

    @Test
    fun `404 는 catch-all 의 500 으로 떨어지지 않는다`() {
        // 회귀 방지: handleNotFound 가 사라지면 NoResourceFoundException 이 handleUncaught 로 가서 500 이 된다.
        val notFound = handler.handleNotFound(NoResourceFoundException(HttpMethod.GET, "/does-not-exist"))
        val uncaught = handler.handleUncaught(RuntimeException("boom"))

        assertEquals(404, notFound.statusCode.value())
        assertEquals(500, uncaught.statusCode.value())
    }
}
