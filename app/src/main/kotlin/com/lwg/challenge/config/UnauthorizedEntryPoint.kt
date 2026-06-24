package com.lwg.challenge.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.domain.common.ResponseCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

/**
 * 인증이 필요하나 SecurityContext 에 Authentication 이 없을 때 호출되는 진입점.
 *
 * 응답 규약: HTTP 표준 코드(4xx/5xx)는 HTTP status 로 그대로 내리고, 700번대 비즈니스 코드만 HTTP 200 + body.code 로 응답.
 * 인증 실패는 HTTP 401 + body.code=401 — 모바일 Ktor Authenticator 가 401 을 트리거로 refresh 흐름 진입.
 */
@Component
class UnauthorizedEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        val body = BaseResponse(
            error = true,
            code = ResponseCode.UNAUTHORIZED,
            message = "토큰이 만료되었거나 유효하지 않습니다",
        )
        response.writer.write(objectMapper.writeValueAsString(body))
    }
}
