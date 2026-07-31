package com.lwg.challenge.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 시간 의존 로직의 단일 진실 원천.
 *
 * `Instant.now()` 를 서비스 안에서 직접 부르면 자정 경계 같은 시간 의존 분기를 테스트에서 재현할 수 없다.
 * [Clock] 을 주입받게 해 두면 단위 테스트가 `Clock.fixed(...)` 로 임의 시각을 고정할 수 있다
 * (`ChallengeCommandServiceTest` 가 이 방식으로 마감 경과 / KST 자정 경계를 검증한다).
 *
 * UTC 로 고정하는 이유: DB 의 `TIMESTAMP` 컬럼(timezone 없음)을 프로젝트 전체가 UTC 로 간주한다.
 * 서버 로컬 타임존이 무엇이든 저장 값이 흔들리면 안 된다.
 * 챌린지의 "하루" 를 KST 로 끊는 것은 별개 관심사이며 `KstDeadlineCalculator` 가 담당한다.
 */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
