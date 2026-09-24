package com.lwg.challenge.service.challenge

import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.service.verification.MissionVerificationRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 배치 **1회 실행** — 대상 선정과 루프 (challenge-result T-B1).
 *
 * ## 🔴 트랜잭션이 없다. 일부러 없다
 *
 * 건별 트랜잭션은 [ChallengeJudgementService] 가 연다. 여기에 `@Transactional` 을 얹으면
 * **전체가 한 트랜잭션이 되어 한 건이 터질 때 그날 판정한 전부가 롤백된다** — 계약 §3.2 의
 * *"챌린지 1건 = 1 트랜잭션. 한 건이 터져도 나머지는 판정된다"* 가 깨진다.
 * 이 클래스와 [ChallengeJudgementService] 가 별개 빈인 이유도 같은 축이다(그쪽 KDoc 참조).
 *
 * ## 🔴 실패는 세고 넘어간다 — 재시도 큐가 없는 이유
 *
 * 한 건이 예외를 던져도 나머지를 계속 판정한다. 실패한 건은 `status` 가 `IN_PROGRESS` 인 채로
 * 남고, 대상 선정 기준이 `deadline <= now` 라서 **다음 실행이 자연히 다시 집는다.**
 * 별도의 재시도 큐도, 실패 테이블도, 마지막 실행 시각 기록도 필요 없다.
 *
 * ## ⚠️ 중복 실행 가드는 **단일 인스턴스 전제**다
 *
 * 기동 직후 실행(`ApplicationReadyEvent`)과 정기 cron 이 겹칠 수 있다 — 00:05 근처 재기동이
 * 그 경로다. [running] 이 그걸 막는데, **이건 in-process 플래그라 프로세스 하나에서만 유효하다.**
 * 다중 인스턴스로 늘리면 두 대가 동시에 돌 수 있고, 그때 필요한 건 DB 락(또는 Quartz 잡스토어)
 * 이다 — ADR-0007 (local→AWS phased) 소관이다. 그때까지의 임시 방편은
 * `challenge.result.batch.enabled=false` 로 한 대만 남기는 것이다.
 *
 * (가드가 뚫려도 전적이 두 번 세어지지는 않는다 — [ChallengeJudgementService.judge] 의 멱등
 * 가드가 두 번째 판정을 `skipped` 로 떨군다. 이 플래그는 정확성이 아니라 헛일 방지용이다.)
 */
@Service
class ChallengeJudgementRunner(
    private val challengeRepository: ChallengeRepository,
    private val judgementService: ChallengeJudgementService,
    /**
     * 판정 **앞단**의 AI 검증 단계 (ai-verification T-B4).
     *
     * 🔴 별도 빈인 이유는 그 클래스 KDoc 참조 — 이 클래스는 이미 결정 3개를 지고 있고,
     * 외부 HTTP 호출·타임아웃·fail-open 까지 얹으면 한 클래스가 두 가지를 한다.
     */
    private val missionVerificationRunner: MissionVerificationRunner,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(ChallengeJudgementRunner::class.java)

    /** 실행 중 여부. 위 KDoc 의 "단일 인스턴스 전제" 가 이 필드의 유효 범위다. */
    private val running = AtomicBoolean(false)

    /**
     * 마감이 지난 챌린지를 전부 처리한다.
     *
     * `IN_PROGRESS` → 판정([ChallengeJudgementService.judge]), `PENDING` → 만료
     * ([ChallengeJudgementService.expire]). 두 상태를 한 쿼리로 합치지 않는 이유는 처리가 완전히
     * 달라서다 — 판정은 전적을 집계하고 만료는 집계하지 않는다 (계약 §3.1).
     *
     * 예외를 밖으로 던지지 않는다. 호출자는 스케줄러 스레드와 기동 이벤트라, 여기서 던지면
     * 각각 다음 실행이 막히거나 애플리케이션 기동이 실패한다.
     *
     * @return 이번 실행의 집계. 이미 실행 중이었으면 전부 0 이다.
     */
    fun run(): JudgementRunResult {
        if (!running.compareAndSet(false, true)) {
            log.info("판정 배치가 이미 실행 중이라 이번 트리거는 건너뛴다")
            return JudgementRunResult(judged = 0, expired = 0, failed = 0, skipped = 0)
        }

        try {
            val now = LocalDateTime.now(clock)

            var judged = 0
            var expired = 0
            var failed = 0
            var skipped = 0

            // 🔴 **판정보다 먼저 돈다** (ai-verification T-B4). AI 가 반려한 인증은 여기서
            //    FAILED 로 내려가고, 아래 judge() 가 그 status 를 읽어 승패를 낸다.
            //    순서를 뒤집으면 판정이 끝난 뒤에 검증이 도는 꼴이라 아무 효과가 없다.
            //
            // 🔴 **runCatching 으로 감싼다** — 검증이 판정을 막아서는 안 된다. AI 는 있으면
            //    좋은 것이고, 없으면 이 feature 이전의 규칙(사진이 있으면 통과)으로 돌아갈 뿐이다.
            //    여기서 예외가 새면 그날 자정 배치가 통째로 멈춘다.
            val verification = runCatching { missionVerificationRunner.verifyDueChallenges(now) }
                .onFailure { log.error("AI 인증 검증 단계 실패 — 판정은 그대로 진행한다", it) }
                .getOrNull()

            challengeRepository.findDueByStatus(setOf(ChallengeStatus.IN_PROGRESS), now).forEach { challenge ->
                val id = challenge.id ?: return@forEach
                runCatching { judgementService.judge(id) }
                    .onSuccess { if (it) judged++ else skipped++ }
                    .onFailure {
                        failed++
                        // challengeId 를 반드시 남긴다 — 이게 없으면 다음 실행에서 뭘 봐야 하는지 알 수 없다.
                        log.error("챌린지 판정 실패 (challengeId=$id). status 가 그대로라 다음 실행이 재시도한다", it)
                    }
            }

            challengeRepository.findDueByStatus(setOf(ChallengeStatus.PENDING), now).forEach { challenge ->
                val id = challenge.id ?: return@forEach
                runCatching { judgementService.expire(id) }
                    .onSuccess { if (it) expired++ else skipped++ }
                    .onFailure {
                        failed++
                        log.error("챌린지 만료 실패 (challengeId=$id). status 가 그대로라 다음 실행이 재시도한다", it)
                    }
            }

            val result = JudgementRunResult(
                judged = judged,
                expired = expired,
                failed = failed,
                skipped = skipped,
                aiRejected = verification?.rejected ?: 0,
                aiFailOpen = verification?.failed ?: 0,
            )
            // 🔴 대상이 0 건이면 로그를 남기지 않는다. 매일 자정 "0건 처리" 가 쌓이면
            //    정작 뭔가 일어난 날의 한 줄이 묻힌다.
            if (!result.isEmpty()) {
                log.info("판정 배치 완료 (now=$now): $result")
            }
            return result
        } finally {
            running.set(false)
        }
    }
}

/**
 * 실행 1회의 집계. 로그와 테스트가 숫자로 검증하는 값이다.
 *
 * @param judged `IN_PROGRESS` → `COMPLETED` 로 판정된 건수
 * @param expired `PENDING` → `EXPIRED` 로 정리된 건수
 * @param failed 예외로 실패한 건수. **status 가 그대로라 다음 실행이 재시도한다**
 * @param skipped 대상 목록에는 있었으나 이미 처리돼 있던 건수. 0 이 아니면 판정 트리거가
 *   겹쳤다는 신호다 (동시 실행 가드가 뚫렸거나, 실행이 자정을 넘길 만큼 길었거나)
 * @param aiRejected AI 가 반려해 `FAILED` 로 내린 인증 건수 (ai-verification).
 *   🔴 **챌린지 수가 아니라 인증 수다** — 한 챌린지에서 둘 다 반려되면 2 다.
 *   이 숫자만큼 사람이 지고 되돌릴 경로가 없으므로, 갑자기 튀면 그날 `ai_reason` 을 읽어라
 * @param aiFailOpen 🔴 **AI 판별이 되지 않아 통과시킨 건수 (fail-open).** ADR-0013 §결정 4 가
 *   *"배치 요약에 숫자로 남겨라"* 라고 요구한 값이다. 0 이 아닌 날은 fail-open 이 돌았다는
 *   뜻이고, 가장 흔한 원인은 **Gemini 선불 잔액 소진 또는 12개월 만료**(`429`)다.
 *   그 상태는 조용하다 — 에러도 사용자 불만도 없이 *"아무 사진이나 통과"* 로 복귀해 있다
 */
data class JudgementRunResult(
    val judged: Int,
    val expired: Int,
    val failed: Int,
    val skipped: Int,
    val aiRejected: Int = 0,
    val aiFailOpen: Int = 0,
) {
    /**
     * 아무 일도 일어나지 않았는가. 로그를 남길지 정하는 데만 쓴다.
     *
     * ⚠️ **AI 카운터는 보지 않는다.** 둘 중 하나가 0 이 아니면 판정 대상이 있었다는 뜻이라
     * [judged]/[skipped] 가 이미 0 이 아니다. 여기에 더하면 조건만 길어지고 답은 같다.
     */
    fun isEmpty(): Boolean = judged == 0 && expired == 0 && failed == 0 && skipped == 0
}
