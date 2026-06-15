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
 * Spring Security 기본 동작은 HTTP 401/403. 본 프로젝트는 ADR-0002 에 따라 **항상 HTTP 200 + body.code** 로
 * 응답해야 모바일 `ApiResultCall` 이 일관되게 처리할 수 있으므로 BaseResponse 로 변환한다.
 *
 * `code=401` → 모바일이 refresh 흐름으로 진입.
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
        response.status = HttpServletResponse.SC_OK
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
