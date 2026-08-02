package com.lwg.challenge.controller.auth

import com.lwg.challenge.controller.auth.dto.LoginData
import com.lwg.challenge.controller.auth.dto.LoginResponse
import com.lwg.challenge.controller.auth.dto.TestLoginRequest
import com.lwg.challenge.service.auth.LoginResult
import com.lwg.challenge.service.auth.TestLoginService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import com.lwg.challenge.core.config.TestLoginEnabledCondition
import org.springframework.context.annotation.Conditional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 개발용 테스트 로그인 컨트롤러 (dev-test-login T-B2).
 *
 * ## 🔴 이 빈은 **조건부로만 존재한다**
 *
 * ```
 * challenge.dev.test-login.enabled=true   → 빈 생성 → 엔드포인트 존재
 * (속성 미설정 / false)                    → 빈 미생성 → 라우팅 부재 → 404
 * ```
 *
 * 엔드포인트를 등록해 놓고 내부에서 403 을 주는 방식이 **아니다.** 애초에 라우팅에 없어야 한다는 게
 * spec 의 격리 조건 2다. 빈이 없으면 SpringDoc 이 스캔할 대상도 없어 **`/v3/api-docs` 에도 안 보인다.**
 *
 * 꺼진 환경에서 이 경로가 **404** 를 주려면 두 가지가 더 필요하다 (api-contract §0.2):
 * - `SecurityConfig` 의 **상시** permitAll — 조건부로 묶으면 꺼졌을 때 401 이 나가 목표를 놓친다
 * - `GlobalExceptionHandler.handleNotFound` — 없으면 catch-all 이 500 으로 삼킨다
 *
 * ## 응답 shape
 *
 * `POST /api/v1/auth/kakao` 와 **완전히 동일한** [LoginResponse] 를 쓴다. 모바일이 기존 매퍼·후처리를
 * 그대로 타야 "테스트 로그인으로 검증한 것이 실제 로그인을 보증"한다.
 */
@RestController
@RequestMapping("/api/v1")
@Conditional(TestLoginEnabledCondition::class)
@Tag(name = "Auth (dev)", description = "개발용 테스트 로그인 — 설정을 켠 환경에서만 존재")
class TestLoginController(
    private val testLoginService: TestLoginService,
) {

    @PostMapping("/auth/test-login")
    @Operation(
        summary = "개발용 테스트 로그인 (조건부 존재)",
        description = "가짜 계정(kakao_id 999000001~3)으로 즉시 access + refresh 를 발급한다. " +
            "계정이 없으면 만들고 있으면 재사용한다(멱등). 카카오 검증을 하지 않는 인증 우회 경로이므로 " +
            "`challenge.dev.test-login.enabled=true` 인 환경에서만 등록된다.",
    )
    fun testLogin(
        @Valid @RequestBody request: TestLoginRequest,
    ): LoginResponse {
        val result: LoginResult = testLoginService.login(request.testUserNo!!)
        return LoginResponse(
            data = LoginData(
                accessToken = result.accessToken,
                refreshToken = result.refreshToken,
                userId = result.userId,
                isNewUser = result.isNewUser,
            ),
        )
    }
}
