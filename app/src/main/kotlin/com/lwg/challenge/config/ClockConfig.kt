package com.lwg.challenge.config

import com.lwg.challenge.core.time.KstTime
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 시간 의존 로직의 단일 진실 원천 (ADR-0010).
 *
 * `Instant.now()` / `LocalDateTime.now()` 를 서비스 안에서 직접 부르면 자정 경계 같은 시간 의존
 * 분기를 테스트에서 재현할 수 없다. [Clock] 을 주입받게 해 두면 단위 테스트가
 * `Clock.fixed(..., KstTime.ZONE)` 으로 임의 시각을 고정할 수 있다.
 *
 * ## UTC → KST 로 바뀌었다 (2026-07-31)
 *
 * 이전 구현은 `Clock.systemUTC()` 였다. 당시 문서화된 규약("DB TIMESTAMP 를 UTC 로 간주")을
 * 따른 것인데, **레포의 나머지 코드는 그 규약을 한 번도 지키지 않았다** — 엔티티 기본값과
 * `FriendService`/`AuthService` 가 전부 `LocalDateTime.now()`(JVM 기본 = KST)를 썼다.
 * 결과적으로 같은 DB 에 9시간 다른 두 기준이 공존했다 (challenges row 0 이라 실害는 없었다).
 *
 * ADR-0010 이 기준을 **KST 단일**로 확정했으므로 이 빈도 KST 로 맞춘다.
 * 이제 `LocalDateTime.now(clock)` 은 어디서 부르든 KST 벽시계를 준다.
 *
 * **JVM 기본 타임존에 의존하지 않는다** — `KstTime.ZONE` 을 명시적으로 넘기므로 AWS 에서
 * JVM 이 UTC 로 떠도 값이 밀리지 않는다 (api-contract §1.4 의 정본 경로).
 */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.system(KstTime.ZONE)
}
