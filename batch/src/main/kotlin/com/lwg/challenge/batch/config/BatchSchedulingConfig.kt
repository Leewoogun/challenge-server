package com.lwg.challenge.batch.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * **이 프로젝트에서 스케줄링을 켜는 유일한 지점이다** (challenge-result T-B1).
 *
 * `@EnableScheduling` 이 없으면 `@Scheduled` 어노테이션은 **아무 오류 없이 그냥 무시된다.**
 * 컴파일도 되고 기동도 되고 컨텍스트도 뜬다 — 배치만 영원히 돌지 않는다. 이 프로젝트가
 * 반복해서 겪은 "테스트는 초록인데 실제로는 안 됨" 계열이라 한 곳에 모아 두고 이름으로 못박는다.
 *
 * ## 왜 `:app` 이 아니라 `:batch` 에 있나
 *
 * 스케줄링은 배치 모듈의 관심사고, 그 관심사가 필요한 이유(`@Scheduled` 빈)가 이 모듈에만 있다.
 * `:app` 의 `@SpringBootApplication` 이 `com.lwg.challenge` 하위를 전부 스캔하므로 여기 두어도
 * 자동으로 잡힌다 (`ChallengeServerApplication` KDoc).
 *
 * ⚠️ 두 번째 `@EnableScheduling` 을 다른 모듈에 추가하지 마라. 중복 자체는 무해하지만
 * "스케줄링이 켜져 있는가" 를 확인할 지점이 둘로 갈린다.
 *
 * ## 스레드 풀은 기본값 그대로다 — 단일 스레드다
 *
 * Spring 기본 `TaskScheduler` 는 스레드 1개다. 지금 `@Scheduled` 가 하나뿐이라 충분하고,
 * 오히려 잡이 겹쳐 도는 일이 없어 안전한 쪽이다. 잡이 늘어 서로를 밀어내기 시작하면
 * 그때 `ThreadPoolTaskScheduler` 빈을 여기에 추가한다.
 */
@Configuration
@EnableScheduling
class BatchSchedulingConfig
