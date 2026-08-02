package com.lwg.challenge.controller.auth

import com.lwg.challenge.core.config.TestLoginEnabledCondition
import com.lwg.challenge.service.auth.TestLoginService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.mock.env.MockEnvironment

/**
 * 🔴 **격리 검증** (dev-test-login T-B1/T-B3) — 이 feature에서 기능보다 중요한 테스트다.
 *
 * spec: *"켠 상태 동작과 끈 상태 부재를 각각 테스트로 고정해라. **후자가 더 중요하다** —
 * 켜진 건 눈에 보이지만 꺼진 건 검증 안 하면 아무도 모른다."*
 *
 * [TestLoginEnabledCondition] 을 **실제 Spring 컨텍스트에 태워** 빈 생성 여부를 확인한다.
 * 조건 클래스의 `matches()` 만 직접 호출하면 "Spring 이 실제로 이 조건을 존중하는가"를 검증하지 못한다.
 */
class TestLoginIsolationTest {

    /** 조건이 붙은 더미 빈. 실제 컨트롤러/서비스와 **같은 조건**을 쓴다. */
    @Configuration
    open class GatedConfig {
        @Conditional(TestLoginEnabledCondition::class)
        @org.springframework.context.annotation.Bean
        open fun gatedBean(): String = "test-login-enabled"
    }

    private fun contextWith(vararg props: Pair<String, String>): AnnotationConfigApplicationContext {
        val env = MockEnvironment()
        props.forEach { (k, v) -> env.setProperty(k, v) }
        return AnnotationConfigApplicationContext().apply {
            environment = env
            register(GatedConfig::class.java)
            refresh()
        }
    }

    // ─── 🔴 끈 상태 = 기본값. 이게 제일 중요하다 ──────────────────

    @Test
    fun `속성을 설정하지 않으면 빈이 생성되지 않는다 (fail-closed 기본값)`() {
        // "운영에서 끄는 걸 잊으면 열리는" 구조가 아님을 보장하는 핵심 단언.
        contextWith().use { ctx ->
            assertFalse(
                ctx.containsBean("gatedBean"),
                "속성 미설정 상태에서 빈이 생성됐다 — fail-closed 위반",
            )
        }
    }

    @Test
    fun `속성이 false 면 빈이 생성되지 않는다`() {
        contextWith(TestLoginEnabledCondition.PROPERTY_NAME to "false").use { ctx ->
            assertFalse(ctx.containsBean("gatedBean"))
        }
    }

    @Test
    fun `true 가 아닌 값에는 열리지 않는다`() {
        // "1"/"yes"/"on" 도 제외한다 — 보안 경계라 켜는 방법이 하나뿐인 편이 낫다.
        for (value in listOf("1", "yes", "on", "0", "no", "off", "enabled", "", "tru", "false")) {
            contextWith(TestLoginEnabledCondition.PROPERTY_NAME to value).use { ctx ->
                assertFalse(
                    ctx.containsBean("gatedBean"),
                    "속성값 '$value' 로 빈이 생성됐다 — \"true\" 만 허용해야 한다",
                )
            }
        }
    }

    /**
     * 🔴 오타가 **서버를 죽이면 안 된다**.
     *
     * `environment.getProperty(name, Boolean::class.java, false)` 를 쓰면 `"enabled"` 나 `""` 같은
     * 값에서 `ConversionFailedException` 이 발생해 **애플리케이션이 기동 중 죽는다**(2026-07-31 실측).
     * dev 전용 플래그의 오타가 서버 전체를 내리는 건 과한 실패 모드다.
     *
     * fail-closed 는 "안 열린다"여야지 "터진다"가 아니다 — 인식 못 하는 값은 전부 꺼짐으로 수렴해야 한다.
     */
    @Test
    fun `인식할 수 없는 값이어도 예외 없이 꺼진 상태가 된다`() {
        for (value in listOf("enabled", "", "  ", "yes-please", "!@#")) {
            // 컨텍스트 생성 자체가 예외 없이 끝나야 한다.
            contextWith(TestLoginEnabledCondition.PROPERTY_NAME to value).use { ctx ->
                assertFalse(ctx.containsBean("gatedBean"), "속성값 '$value'")
            }
        }
    }

    @Test
    fun `앞뒤 공백은 무시한다`() {
        contextWith(TestLoginEnabledCondition.PROPERTY_NAME to "  true  ").use { ctx ->
            assertTrue(ctx.containsBean("gatedBean"))
        }
    }

    // ─── 켠 상태 ─────────────────────────────────────────────────

    @Test
    fun `속성이 true 면 빈이 생성된다`() {
        contextWith(TestLoginEnabledCondition.PROPERTY_NAME to "true").use { ctx ->
            assertTrue(ctx.containsBean("gatedBean"))
            assertEquals("test-login-enabled", ctx.getBean("gatedBean"))
        }
    }

    @Test
    fun `대소문자 무관하게 TRUE 도 허용된다`() {
        // Boolean 바인딩은 대소문자를 구분하지 않는다. 켜려는 의도가 분명한 값이므로 허용이 맞다.
        contextWith(TestLoginEnabledCondition.PROPERTY_NAME to "TRUE").use { ctx ->
            assertTrue(ctx.containsBean("gatedBean"))
        }
    }

    // ─── 조건 이름이 계약과 일치하는가 ───────────────────────────

    @Test
    fun `속성 이름이 계약과 일치한다`() {
        // 이름이 바뀌면 운영 설정이 조용히 무력화된다(= 열려 있어야 할 때 안 열리거나 그 반대).
        assertEquals("challenge.dev.test-login.enabled", TestLoginEnabledCondition.PROPERTY_NAME)
    }

    // ─── 계정 규약 ───────────────────────────────────────────────

    @Test
    fun `테스트 계정 규약이 계약과 일치한다`() {
        assertEquals(1, TestLoginService.MIN_TEST_USER_NO)
        assertEquals(3, TestLoginService.MAX_TEST_USER_NO)
        assertEquals(999_000_000L, TestLoginService.KAKAO_ID_BASE)
        // kakao_id 999000001~3 / 닉네임 테스터1~3 — 모바일 버튼 라벨과 글자 단위로 같아야 한다.
        assertEquals("테스터1", TestLoginService.nicknameFor(1))
        assertEquals("테스터2", TestLoginService.nicknameFor(2))
        assertEquals("테스터3", TestLoginService.nicknameFor(3))
        assertEquals(999_000_001L, TestLoginService.KAKAO_ID_BASE + 1)
        assertEquals(999_000_003L, TestLoginService.KAKAO_ID_BASE + 3)
    }
}
