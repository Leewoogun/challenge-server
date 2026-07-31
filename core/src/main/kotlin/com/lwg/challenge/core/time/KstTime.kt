package com.lwg.challenge.core.time

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 프로젝트 전역의 시각 기준 (ADR-0010).
 *
 * ## 왜 이게 필요한가
 *
 * `LocalDateTime.now()` 는 **JVM 기본 타임존**을 따른다. 개발 머신이 `Asia/Seoul` 이라 지금까지
 * 우연히 맞아 왔지만, ADR-0007 의 AWS 배포에서 JVM 이 UTC 로 뜨면 **9시간이 조용히 어긋난다.**
 * 컴파일러도 테스트도 잡아주지 못한다 — 값이 그냥 9시간 다를 뿐이기 때문이다.
 *
 * 실제로 이 프로젝트는 한 번 갈라진 적이 있다 (2026-07-31 실측):
 * - `ChallengeCommandService` 는 주입된 `Clock.systemUTC()` → **UTC**
 * - 엔티티 기본값 5곳과 `FriendService`/`AuthService` 는 `LocalDateTime.now()` → **KST**
 *
 * 같은 DB 에 9시간 다른 두 기준이 공존했다. `challenges` row 가 0 이라 실害가 없었을 뿐이다.
 *
 * ## 사용 규칙
 *
 * - **Spring 빈이면 `Clock` 을 주입받아 `LocalDateTime.now(clock)` 을 써라.** 테스트에서
 *   `Clock.fixed(...)` 로 시각을 고정할 수 있어 자정 경계 같은 분기를 결정적으로 검증할 수 있다.
 * - **주입이 불가능한 곳(JPA 엔티티 필드 기본값 등)에서만 [now] / [today] 를 써라.**
 * - **`LocalDateTime.now()` 를 인자 없이 호출하지 마라.** 그게 갈라짐의 원인이다.
 *
 * JVM 기본 타임존을 KST 로 고정하는 안전망이 `ChallengeServerApplication` 에 있지만,
 * 그건 어디까지나 **안전망**이고 정본은 이 클래스와 `Clock` 빈이다 —
 * 즉 안전망을 걷어내도 시각이 밀리지 않아야 한다 (api-contract §1.4).
 */
object KstTime {

    /** 서비스 고정 타임존. 해외 대응은 명시적 비범위(ADR-0010). */
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    /** 현재 KST 벽시계 시각. JVM 기본 타임존과 무관하다. */
    fun now(): LocalDateTime = LocalDateTime.now(ZONE)

    /** 오늘 (KST 기준 날짜). 챌린지의 "하루" 는 항상 KST 로 끊는다. */
    fun today(): LocalDate = LocalDate.now(ZONE)
}
