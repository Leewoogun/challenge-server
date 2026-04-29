package com.lwg.challenge

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * 진입점.
 *
 * `@SpringBootApplication`의 기본 `@ComponentScan` / `@EntityScan` / JPA Repository scan은
 * 이 클래스의 패키지(`com.lwg.challenge`) 하위 전체를 스캔한다.
 * `:infra` 모듈의 `UserEntity` / `UserJpaRepository`도 같은 루트 패키지 트리에 있으므로
 * 자동으로 감지된다 — 별도 `@EntityScan` / `@EnableJpaRepositories`를 달지 않는다.
 *
 * (달면 `DataSource`를 제외한 테스트에서도 Repository bean 생성을 강제로 시도하여
 *  context 로드가 실패한다.)
 */
@SpringBootApplication
class ChallengeServerApplication

fun main(args: Array<String>) {
	runApplication<ChallengeServerApplication>(*args)
}
