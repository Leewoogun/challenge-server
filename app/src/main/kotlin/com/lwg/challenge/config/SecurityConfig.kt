package com.lwg.challenge.config

import com.lwg.challenge.api.auth.JwtAuthenticationFilter
import com.lwg.challenge.api.auth.JwtTokenProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

// Spring Security + JWT.
// - CSRF / formLogin / httpBasic disabled
// - Session STATELESS
// - Public: auth endpoints, swagger, actuator health — 그 외 전부 authenticated (opt-in 공개)
// - JwtAuthenticationFilter는 bean 등록하지 않고 여기서 직접 생성 (Servlet 자동 등록 이중 실행 방지)
// - CORS: allow all origins for local dev (ADR-0007 Phase 2 will restrict in prod)
@Configuration
class SecurityConfig(
    private val jwtTokenProvider: JwtTokenProvider,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                val swaggerUiAll = "/swagger-ui/" + "**"
                val apiDocsAll = "/v3/api-docs/" + "**"
                auth
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/kakao", "/api/v1/auth/refresh").permitAll()
                    .requestMatchers(
                        swaggerUiAll,
                        "/swagger-ui.html",
                        apiDocsAll,
                        "/actuator/health",
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(
                JwtAuthenticationFilter(jwtTokenProvider),
                UsernamePasswordAuthenticationFilter::class.java,
            )

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOriginPatterns = listOf("*")
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            allowCredentials = true
            maxAge = 3600
        }
        val source = UrlBasedCorsConfigurationSource()
        val allPaths = "/" + "**"
        source.registerCorsConfiguration(allPaths, configuration)
        return source
    }
}
