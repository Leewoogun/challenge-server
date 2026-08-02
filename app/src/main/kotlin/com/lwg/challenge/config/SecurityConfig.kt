package com.lwg.challenge.config

import com.lwg.challenge.controller.auth.JwtAuthenticationFilter
import com.lwg.challenge.core.auth.JwtTokenProvider
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
    private val unauthorizedEntryPoint: UnauthorizedEntryPoint,
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
                    // ── 개발용 테스트 로그인 (dev-test-login T-B1a) ──
                    //
                    // ⚠️ 이 permitAll 은 **플래그와 무관하게 상시**다. 조건부로 묶으면 안 된다.
                    //
                    // 컨트롤러 빈은 `challenge.dev.test-login.enabled=true` 일 때만 생성된다(fail-closed).
                    // 그런데 permitAll 까지 같은 조건으로 묶으면, 꺼진 환경에서 요청이 Security 에 걸려
                    // **401** 이 나간다. 우리가 원하는 건 "그런 경로가 없다"는 **404** 다.
                    // 404 가 나오려면 꺼진 상태에서도 Security 를 통과해 라우팅까지 도달해야 한다.
                    //
                    // 401 이면 모바일 Ktor Auth(bearer) 가 "access 만료"로 오인해 refresh 를 시도하고,
                    // 로그인 화면엔 refresh token 이 없어 `emitSessionExpired()` → 로그인 화면 리셋으로 이어진다.
                    // **경로가 없을 뿐인데 앱이 "세션 만료"라는 틀린 결론을 낸다.**
                    //
                    // 🔒 **실제 노출을 막는 것은 이 permitAll 이 아니라 "빈이 존재하지 않는다"는 사실이다.**
                    //    핸들러가 없으면 permitAll 은 "인증 없이 라우팅까지 도달"만 허용하고 그 끝은 404 다.
                    //
                    // ⚠️ 따라서 **이 경로에 다른 핸들러를 매핑하면 인증 없이 공개된다.** 매핑 시 반드시 재검토할 것.
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/test-login").permitAll()
                    .requestMatchers(
                        swaggerUiAll,
                        "/swagger-ui.html",
                        apiDocsAll,
                        "/actuator/health",
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            // 인증 실패는 HTTP 401 + body.code=401 (UnauthorizedEntryPoint). 모바일 Ktor Authenticator 가 401 트리거로 refresh 진입.
            .exceptionHandling { it.authenticationEntryPoint(unauthorizedEntryPoint) }
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
