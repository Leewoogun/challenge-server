package com.lwg.challenge.core.config

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * 개발용 테스트 로그인 활성화 조건 (dev-test-login T-B1).
 *
 * ```
 * challenge.dev.test-login.enabled=true   → 빈 생성
 * (속성 미설정 / false / 그 외 값)          → 빈 미생성
 * ```
 *
 * ## 🔒 fail-closed 가 이 클래스의 존재 이유다
 *
 * `getProperty(..., defaultValue = false)` 로 **속성이 없으면 무조건 `false`** 다.
 * 운영에서 "끄는 것을 잊어" 열리는 경로가 생기지 않는다 — 끄는 게 아니라 **켜야 열린다.**
 *
 * ## 왜 Spring 프로파일이 아닌가
 *
 * 프로파일은 시간이 지나며 의미가 누적된다. 누군가 로깅·목데이터·CORS 때문에 `dev` 프로파일을
 * 켜는 순간 **인증 우회까지 딸려 켜진다.** 단일 목적 속성은 그 사고를 구조적으로 막는다.
 * (레포에 프로파일 사용처가 0건이라 체계를 새로 도입하는 비용도 있었다. api-contract §0.1 참조.)
 *
 * ## 왜 `@ConditionalOnProperty` 가 아닌가
 *
 * 그 어노테이션은 `spring-boot-autoconfigure` 에 있는데 **`:service` 모듈에는 그 의존성이 없다.**
 * 어노테이션 하나 때문에 모듈 의존성을 늘리는 대신, `spring-context` 만으로 되는 [Condition] 을 쓴다.
 * `:core` 에 두어 `:service` 와 `:controller` 가 **같은 정의를 공유**한다 — 두 곳의 조건이 갈리면
 * 컨트롤러는 있는데 서비스가 없는(또는 그 반대) 상태가 생길 수 있다.
 */
class TestLoginEnabledCondition : Condition {

    /**
     * **문자열 `"true"` 와 정확히 일치할 때만** 켠다 (대소문자·앞뒤 공백은 무시).
     *
     * `environment.getProperty(name, Boolean::class.java, false)` 를 쓰지 않은 이유가 있다.
     * 실측(2026-07-31)한 Spring 의 변환 동작:
     * ```
     * "true"/"TRUE"/"1"/"yes"/"on"  → true
     * "0"/"no"/"off"                → false
     * "enabled" / ""                → ConversionFailedException  ← 던진다!
     * (미설정)                       → false
     * ```
     * **오타 하나로 애플리케이션이 기동 중 죽는다.** `enabled=enable` 같은 실수가 서버 전체를
     * 내려버리는 건 dev 전용 플래그의 실패 모드로 과하다. fail-closed 는 "안 열린다"여야지
     * "터진다"가 아니다.
     *
     * 문자열 비교로 두면 **인식 못 하는 값은 전부 꺼짐**이 되어 실패 모드가 하나로 수렴한다.
     * Spring 변환기 구현이 바뀌어도 이 조건의 동작은 흔들리지 않는다는 이점도 있다.
     *
     * `"1"`/`"yes"`/`"on"` 을 잃지만, 이 속성은 **보안 경계**라 켜는 방법이 하나뿐인 편이 낫다.
     */
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        context.environment.getProperty(PROPERTY_NAME)?.trim()?.equals("true", ignoreCase = true) == true

    companion object {
        const val PROPERTY_NAME = "challenge.dev.test-login.enabled"
    }
}
