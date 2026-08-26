package com.lwg.challenge.batch.challenge

import com.lwg.challenge.service.challenge.ChallengeJudgementRunner
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 자정 직후 판정 배치 트리거 — **이 프로젝트의 첫 스케줄러다** (challenge-result T-B1, 계약 §3.2).
 *
 * 선례가 되는 자리라 "무엇을 하는가" 보다 **무엇을 고르지 않았고 왜인지**를 남긴다.
 *
 * ## 왜 `@Scheduled` 인가 (Spring Batch / Quartz 를 쓰지 않은 이유)
 *
 * - **Spring Batch**: chunk 처리·재시작·`JobRepository` 6테이블이 통째로 따라온다. 이 잡이 하는
 *   일은 *"행 몇 개 읽고 상태 바꾸기"* 라서 그 기계장치가 사는 값을 못 한다. 스키마가 6개 늘고
 *   마이그레이션이 하나 늘어나는 비용은 지금 얻는 것(재시작 가능성)보다 크다 —
 *   재시도는 이미 `deadline <= now` 쿼리가 공짜로 준다.
 * - **Quartz(DB 잡스토어)**: 존재 이유가 **다중 인스턴스 중복 실행 방지**인데 지금은 단일
 *   인스턴스다 (ADR-0007 local→AWS phased). 필요해지는 시점에 갈아끼울 지점이 이 클래스 하나뿐이라
 *   미리 도입할 이유가 없다.
 * - **`@Scheduled`**: 새 의존성 0, `zone` 으로 KST 명시 가능, 읽는 사람이 cron 한 줄만 보면 된다.
 *
 * ## 🔴 소급 판정은 스케줄러가 아니라 **쿼리**가 보장한다
 *
 * cron 은 *"언제 볼까"* 만 정한다. 대상은 `ChallengeRepository.findDueByStatus` 가
 * `deadline <= now` 로 뽑으므로, 서버가 자정에 죽어 있었든 사흘을 죽어 있었든 **밀린 분이 전부
 * 대상에 들어온다.** 여기에 [runOnStartup] 을 더해, *"자정에 죽어 있다가 아침에 올라온"* 경우
 * 다음 자정까지 기다리지 않는다.
 *
 * ⚠️ 따라서 cron 을 놓친 실행을 보상하는 로직을 여기에 추가하지 마라 — 이미 쿼리가 하고 있고,
 * 두 번째 보상 로직은 두 번 도는 원인이 될 뿐이다.
 *
 * ## 🔴 cron 의 `zone` 을 반드시 명시한다
 *
 * `@Scheduled(cron)` 의 기본 타임존은 **JVM 기본**이다. `main()` 의 `TimeZone.setDefault` 는
 * 어디까지나 안전망이고(ADR-0010), 정본은 이 명시다 — **그 줄을 지워도 00:05 KST 에 돌아야 한다.**
 * 명시하지 않으면 AWS(UTC) 배포에서 조용히 09:05 KST 에 돌게 되는데, 컴파일러도 테스트도
 * 잡아주지 못한다.
 */
@Component
@ConditionalOnProperty(
    prefix = "challenge.result.batch",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ChallengeJudgementScheduler(
    private val runner: ChallengeJudgementRunner,
) {

    private val log = LoggerFactory.getLogger(ChallengeJudgementScheduler::class.java)

    /**
     * 정기 실행 — 기본 매일 00:05 KST (`challenge.result.batch.cron`).
     *
     * 마감이 익일 00:00 이므로 5분 여유를 둔다. 정각에 돌면 `deadline == now` 동시성을 실제로
     * 만나게 되는데, 그 경계 논의를 아예 발생하지 않는 문제로 만드는 것이 이 5분의 값이다.
     */
    @Scheduled(cron = "\${challenge.result.batch.cron}", zone = KST_ZONE)
    fun runScheduled() {
        trigger("cron")
    }

    /**
     * 기동 시 소급 1회 (계약 §3.2 *"서버가 자정에 죽어 있었어도 기동 즉시 소급 판정"*).
     *
     * `ApplicationReadyEvent` 인 이유: 컨텍스트가 완전히 뜨고 나서 실행돼야 한다.
     * `ContextRefreshedEvent` 는 초기화 도중이라 DB 커넥션 풀이나 트랜잭션 매니저가 아직
     * 준비되지 않은 상태에서 배치를 돌릴 수 있다.
     *
     * ⚠️ 재기동이 잦으면 그만큼 자주 돈다. 헛일이지 오작동은 아니다 —
     * 대상이 없으면 쿼리 두 번으로 끝나고 로그도 남지 않는다.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun runOnStartup() {
        trigger("startup")
    }

    /**
     * 🔴 **예외를 밖으로 던지지 않는다.**
     *
     * `@Scheduled` 메서드에서 던진 예외는 그 잡의 **다음 실행을 취소한다** — 하루 실패가
     * 영구 중단이 된다. `ApplicationReadyEvent` 리스너에서 던지면 **기동 자체가 실패한다** —
     * 배치 하나 때문에 서버가 안 뜬다.
     *
     * [ChallengeJudgementRunner.run] 이 이미 건별 예외를 삼키므로 여기까지 올라오는 건
     * 대상 선정 쿼리 실패 같은 것뿐이다. 그것도 다음 실행이 재시도하면 되는 종류다.
     */
    private fun trigger(source: String) {
        runCatching { runner.run() }
            .onSuccess { log.info("판정 배치 트리거($source) 완료: $it") }
            .onFailure { log.error("판정 배치 트리거($source) 실패. 다음 실행이 재시도한다", it) }
    }

    companion object {
        /**
         * 🔴 `KstTime.ZONE.id` 와 **같은 값이어야 한다.**
         *
         * 참조로 묶지 못하는 것은 `@Scheduled(zone = ...)` 이 어노테이션 인자라 **컴파일 상수만**
         * 받기 때문이다. `KstTime.ZONE` 은 런타임 `ZoneId` 객체라 여기에 쓸 수 없다.
         * 한쪽만 바꾸면 배치 시각과 도메인 시각이 갈리므로, 타임존을 옮기는 날 이 상수도 함께 고쳐라.
         */
        const val KST_ZONE = "Asia/Seoul"
    }
}
