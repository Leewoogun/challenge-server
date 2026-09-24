package com.lwg.challenge.service.verification

import com.lwg.challenge.domain.verification.MissionVerdict
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationRepository
import com.lwg.challenge.domain.verification.VerificationStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * AI 판정 결과 기록의 **트랜잭션 경계**만 담당하는 협력 빈 (ai-verification T-B4).
 *
 * 🔴 **별도 빈인 이유는 하나뿐이다 — self-invocation 이 프록시를 타지 않기 때문이다.**
 * 이 메서드가 [MissionVerificationRunner] 안에 있었다면 `this.record(...)` 호출에서
 * `@Transactional` 이 **조용히 무시된다.** 어노테이션은 그대로 붙어 있고 컴파일도 되고 테스트도
 * 초록인데 운영에서만 경계가 없다 — `VerificationSubmitTransactionalWorker` ·
 * `ChallengeJudgementService` 와 같은 이유·같은 모양이다.
 *
 * 반대로 [MissionVerificationRunner] 바깥에 `@Transactional` 을 붙이면 **외부 HTTP 호출이
 * 트랜잭션 안으로 들어와** 네트워크 I/O 동안 DB 커넥션을 붙잡는다. 양쪽 다 틀리므로 구조로 막는다.
 *
 * 그러니 이 클래스를 "불필요한 래퍼" 로 보고 합치지 마라.
 */
@Service
class MissionVerdictRecorder(
    private val verificationRepository: VerificationRepository,
) {

    /**
     * 판정 결과를 row 에 남기고, 반려면 `status` 를 `FAILED` 로 내린다.
     *
     * ## 🔴 `FAILED` 로 내리는 것이 이 feature 가 판정에 개입하는 **유일한 지점**이다
     *
     * `ChallengeJudgementService` 는 *"status 가 VERIFIED 인가"* 두 개만 읽어 승패를 낸다.
     * 여기서 `FAILED` 로 내리면 그 입력이 뒤집히고, **판정 규칙 자체는 한 줄도 바뀌지 않는다**
     * (ADR-0013 §근거 2). 🔴 그러니 `ChallengeVerdict` 나 `judge()` 가 `ai_verdict` 를 읽게
     * 만들지 마라 — 그 순간 *"미인증이란 무엇인가"* 가 두 곳으로 갈린다.
     *
     * ⚠️ 양쪽이 다 반려되면 `BOTH_LOSE` 다. 무승부가 아니다 — 사용자가 명시적으로 확인하고
     * 확정했다 (2026-09-08, spec §1). **버그로 읽고 무승부로 고치지 마라.**
     *
     * ## 🔴 [aiCheckedAt] 은 **판정에 실패해도 찍는다**
     *
     * 멱등의 근거는 *"성공했는가"* 가 아니라 *"불렀는가"* 다. 실패한 건에 안 찍으면 Gemini 가
     * 죽어 있는 동안 매 실행이 같은 건을 다시 부른다. `ai_verdict = ERROR` 가 *"불렀고
     * 실패했다"* 를 구분해 준다.
     *
     * @param now 판정 시각. **KST 벽시계** (ADR-0010). 배치의 기준 시각을 그대로 받는다 —
     *   여기서 `LocalDateTime.now()` 를 새로 부르면 같은 실행 안에서 값이 갈린다.
     * @return 저장된 row.
     */
    @Transactional
    fun record(verification: Verification, verdict: MissionVerdict, now: LocalDateTime): Verification =
        verificationRepository.save(
            verification.copy(
                status = if (verdict.rejectsVerification()) {
                    VerificationStatus.FAILED
                } else {
                    verification.status
                },
                aiVerdict = verdict.outcome,
                aiConfidence = verdict.confidence,
                aiReason = verdict.reason,
                aiCheckedAt = now,
            ),
        )
}
