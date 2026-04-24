package com.lwg.challenge.api.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authorization 헤더의 Bearer Access Token을 검증하여 SecurityContext에 Authentication 주입.
 *
 * 토큰 없거나 invalid여도 필터 자체는 통과 — 실제 접근 차단은 SecurityFilterChain의 authorizeHttpRequests가 담당.
 * (Sprint 0에서는 principal에 userId(Long)만 박는다. 추후 UserDetails 커스텀 구현으로 확장 가능.)
 *
 * Spring bean으로 등록하지 않고 SecurityConfig에서 직접 생성해 addFilterBefore로 주입한다.
 * (@Component로 등록하면 Servlet 자동 등록 + Security 체인 양쪽에서 필터가 이중 실행된다.)
 */
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        resolveToken(request)?.let { token ->
            val userId = jwtTokenProvider.verifyAndGetUserId(
                token,
                expectedType = JwtTokenProvider.TOKEN_TYPE_ACCESS,
            )
            if (userId != null) {
                val authentication = UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER")),
                )
                SecurityContextHolder.getContext().authentication = authentication
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val header = request.getHeader(HEADER_AUTHORIZATION) ?: return null
        return if (header.startsWith(BEARER_PREFIX)) header.substring(BEARER_PREFIX.length) else null
    }

    companion object {
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }
}
