package com.lwg.challenge

import com.lwg.challenge.core.time.KstTime
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.util.TimeZone

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
	// ── JVM 기본 타임존 KST 고정 (ADR-0010 / api-contract §1.4 의 (c) 중 "안전망") ──
	//
	// 이건 **안전망일 뿐 정본이 아니다.** 정본은 `Clock` 빈(KST)과 `KstTime` 이며,
	// 둘 다 ZoneId 를 명시적으로 넘기므로 이 줄을 지워도 시각이 밀리지 않아야 한다.
	//
	// 그럼에도 두는 이유: `LocalDateTime.now()` 를 인자 없이 부르는 코드가 새로 유입되거나
	// 서드파티 라이브러리가 JVM 기본 타임존을 참조할 때, AWS(UTC) 환경에서 9시간 어긋나는 대신
	// 조용히 옳은 값이 되게 한다. 실패 모드를 "9시간 오차" 에서 "정상" 으로 바꾸는 장치다.
	TimeZone.setDefault(TimeZone.getTimeZone(KstTime.ZONE))

	runApplication<ChallengeServerApplication>(*args)
}
