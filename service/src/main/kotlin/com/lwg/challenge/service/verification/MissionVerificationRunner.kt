package com.lwg.challenge.service.verification

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.photo.PhotoStorage
import com.lwg.challenge.domain.verification.MissionVerdict
import com.lwg.challenge.domain.verification.MissionVerifier
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationRepository
import com.lwg.challenge.domain.verification.VerificationStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * 자정 판정 배치의 **앞단 검증 단계** (ai-verification T-B4, ADR-0013 §결정 3).
 *
 * 마감이 지난 `IN_PROGRESS` 챌린지의 **제출된 인증 최종본 1장씩**을 AI 로 판별하고, 반려된
 * 건의 `status` 를 `FAILED` 로 내린다. 그 다음 `ChallengeJudgementService.judge` 가 **기존
 * 규칙 그대로** 승패를 낸다.
 *
 * ```
 * [이 클래스] 사진 판별 → VERIFIED 또는 FAILED 로 확정
 *        ↓
 * [ChallengeJudgementService] "VERIFIED 인가" 두 개를 읽어 승패 결정 — 🔴 한 줄도 안 고쳤다
 * ```
 *
 * ## 🔴 왜 `ChallengeJudgementRunner` 안에 인라인하지 않았나
 *
 * 그 클래스는 이미 KDoc 3개 분량의 결정을 지고 있다(트랜잭션 부재 · 실패 격리 · 중복 실행
 * 가드). 여기에 외부 HTTP 호출 · 타임아웃 · fail-open 까지 얹으면 한 클래스가 두 가지를 한다.
 * 분리하면 *"AI 가 어디서 도는가"* 를 한 파일에서 읽을 수 있다.
 *
 * ## 🔴 트랜잭션이 없다. 일부러 없다
 *
 * [MissionVerifier] 는 **외부 HTTP 호출**이라 트랜잭션 안에서 부르면 네트워크 I/O 가 끝날
 * 때까지 DB 커넥션을 붙잡는다. 자정에 20건이면 20번 그런다. 그래서 **호출은 트랜잭션 밖**에서
 * 하고, 결과 기록만 [MissionVerdictRecorder] 가 짧은 트랜잭션으로 연다 —
 * `ChallengeJudgementRunner` ↔ `ChallengeJudgementService` 와 같은 축의 분리다.
 *
 * ## 🔴 기권 건은 여기 오지 않는다 — 그리고 그게 의도다
 *
 * 기권은 즉시 `COMPLETED` 라 [ChallengeRepository.findDueByStatus] 의 `IN_PROGRESS` 대상에서
 * **자연히 빠진다.** 그래서 기권 상대가 올린 사진은 판별을 받지 않고, 미션과 무관한 사진이어도
 * 이긴다. **그 승리는 인증이 아니라 기권에서 나온 것**이라 사진을 볼 이유가 없다
 * (challenge-forfeit spec §3.1). ⚠️ **기권 건을 AI 대상에 되돌려 넣지 마라.**
 *
 * ## 🔴 멱등 — `ai_checked_at` 이 유일한 근거다
 *
 * 값이 있으면 다시 부르지 않는다. 배치를 두 번 돌려도 AI 는 한 번만 불린다
 * (ADR-0013 §결정 3 *"제출된 인증 1건당 정확히 1회"*). 🔴 **호출했지만 실패한 건에도 찍는다** —
 * 안 찍으면 Gemini 가 죽어 있는 동안 매 실행이 같은 건을 끝없이 다시 부른다.
 */
@Service
class MissionVerificationRunner(
    private val challengeRepository: ChallengeRepository,
    private val verificationRepository: VerificationRepository,
    private val photoStorage: PhotoStorage,
    private val missionVerifier: MissionVerifier,
    /** 결과 기록의 **트랜잭션 경계**. 별도 빈인 이유는 그 클래스 KDoc 참조 (self-invocation). */
    private val recorder: MissionVerdictRecorder,
) {

    private val log = LoggerFactory.getLogger(MissionVerificationRunner::class.java)

    /**
     * 마감이 지난 챌린지의 제출된 인증을 전부 판별한다.
     *
     * 예외를 밖으로 던지지 않는다 — 호출자는 판정 배치이고, 여기서 던지면 **검증 실패가 판정
     * 전체를 막는다.** 그건 이 feature 가 만들 수 있는 최악의 실패다(자정 배치 정지).
     *
     * @param now 마감 비교 기준. **KST** (ADR-0010). `ChallengeJudgementRunner` 가 쓰는 값과
     *   같아야 한다 — 다르면 판별한 건과 판정한 건이 어긋난다.
     */
    fun verifyDueChallenges(now: LocalDateTime): VerificationRunResult {
        var verified = 0
        var rejected = 0
        var skipped = 0
        var failed = 0

        val due = runCatching {
            challengeRepository.findDueByStatus(setOf(ChallengeStatus.IN_PROGRESS), now)
        }.getOrElse { e ->
            log.error("AI 검증 대상 조회 실패 — 이번 실행은 검증을 건너뛴다 (판정은 그대로 진행된다)", e)
            return VerificationRunResult(0, 0, 0, 0)
        }
        if (due.isEmpty()) return VerificationRunResult(0, 0, 0, 0)

        val byChallenge = runCatching {
            verificationRepository.findAllByChallengeIds(due.mapNotNull { it.id })
                .groupBy { it.challengeId }
        }.getOrElse { e ->
            log.error("AI 검증 대상 인증 조회 실패 — 이번 실행은 검증을 건너뛴다", e)
            return VerificationRunResult(0, 0, 0, 0)
        }

        due.forEach { challenge ->
            val challengeId = challenge.id ?: return@forEach
            byChallenge[challengeId].orEmpty().forEach { verification ->
                when (val target = classify(challenge, verification)) {
                    is Target.NotApplicable -> Unit
                    is Target.AlreadyChecked -> skipped++
                    is Target.Verifiable -> {
                        // 🔴 건별 격리. 한 건이 터져도 나머지가 검증되고, 터진 건은
                        //    VERIFIED 를 유지한다(fail-open). ChallengeJudgementRunner 와 같은 형태.
                        val verdict = runCatching { verdictFor(target) }
                            .getOrElse { e ->
                                log.error(
                                    "AI 검증 실패 (challengeId={}, userId={}). 이 건은 통과 처리한다",
                                    challengeId,
                                    verification.userId,
                                    e,
                                )
                                MissionVerdict.error("검증 중 예외: ${e.message}")
                            }

                        val recorded = runCatching { recorder.record(verification, verdict, now) }
                            .onFailure { e ->
                                log.error(
                                    "AI 판정 결과 기록 실패 (challengeId={}, userId={}). " +
                                        "ai_checked_at 이 안 남아 다음 실행이 다시 부른다",
                                    challengeId,
                                    verification.userId,
                                    e,
                                )
                            }
                            .getOrNull()

                        when {
                            recorded == null -> failed++
                            verdict.isFailOpen() -> failed++
                            verdict.rejectsVerification() -> {
                                verified++
                                rejected++
                            }
                            else -> verified++
                        }
                    }
                }
            }
        }

        val result = VerificationRunResult(verified, rejected, skipped, failed)
        // 🔴 대상이 0 건이면 로그를 남기지 않는다 — ChallengeJudgementRunner 와 같은 이유다.
        //    매일 "0건" 이 쌓이면 정작 뭔가 일어난 날의 한 줄이 묻힌다.
        if (!result.isEmpty()) {
            log.info("AI 인증 검증 완료 (now=$now): $result")
        }
        return result
    }

    /**
     * 🔴 **AI 호출과 사진 읽기가 전부 여기 있고, 트랜잭션 밖이다.**
     *
     * 사진은 `photo_url`(=key)로 **서버 디스크에서 읽어** 바이트로 넘긴다. URL 을 넘기면
     * 구글이 접근하지 못한다 — 서버가 로컬/내부망이기 때문이다.
     */
    private fun verdictFor(target: Target.Verifiable): MissionVerdict {
        val bytes = photoStorage.read(target.photoKey)
            ?: return MissionVerdict.error("사진 파일을 읽을 수 없습니다 (key=${target.photoKey})")
        return missionVerifier.verify(target.missionText, bytes)
    }

    /**
     * 이 인증이 판별 대상인지 가른다.
     *
     * 대상이 되려면 **넷이 전부** 참이어야 한다:
     * 1. `status == VERIFIED` — 제출된 건만. 미제출 건은 호출 0회 (ADR-0013 §결정 3)
     * 2. `photo_url != null` — 탈퇴로 사진이 지워진 row 는 볼 것이 없다 (mypage T-B2)
     * 3. `ai_checked_at == null` — 멱등
     * 4. 미션 텍스트가 있다 — 없으면 대조할 기준이 없다
     */
    private fun classify(challenge: Challenge, verification: Verification): Target {
        if (verification.status != VerificationStatus.VERIFIED) return Target.NotApplicable
        val photoKey = verification.photoUrl ?: return Target.NotApplicable
        if (verification.aiCheckedAt != null) return Target.AlreadyChecked

        val missionText = missionTextFor(challenge, verification.userId)
        if (missionText.isNullOrBlank()) {
            // `IN_PROGRESS` 인데 미션이 없는 것은 데이터 이상이다 (opponentMission 은 수락 시 채워진다).
            // 조용히 넘기면 매 실행마다 다시 걸리므로, 판별 대상으로 삼되 아래에서 ERROR 로 기록해
            // ai_checked_at 을 남긴다 — 그래야 이상 사실이 컬럼에 박히고 재시도도 멈춘다.
            log.warn(
                "미션 텍스트가 없어 AI 검증을 건너뛴다 (challengeId={}, userId={}). 데이터 이상 가능성",
                challenge.id,
                verification.userId,
            )
            return Target.Verifiable(verification, photoKey, missionText = "")
        }
        return Target.Verifiable(verification, photoKey, missionText)
    }

    private fun missionTextFor(challenge: Challenge, userId: Long): String? = when (userId) {
        challenge.challengerId -> challenge.challengerMission
        challenge.opponentId -> challenge.opponentMission
        // 당사자가 아닌 userId 의 verification row — 있을 수 없다. 대조 기준이 없으니 대상에서 뺀다.
        else -> null
    }

    private sealed interface Target {
        data object NotApplicable : Target
        data object AlreadyChecked : Target
        data class Verifiable(
            val verification: Verification,
            val photoKey: String,
            val missionText: String,
        ) : Target
    }
}

/**
 * 이 판정이 **AI 판별이 이뤄지지 않아 통과된 것**인가 (= fail-open).
 *
 * 🔴 ADR-0013 §결정 4 가 요구하는 *"AI 판별 스킵 건수"* 의 정의다. `UNVERIFIABLE` 을 함께
 * 세는 것은 **`NoOpMissionVerifier` 가 그 값을 돌려주기 때문**이다 — 키가 없어 판별이 통째로
 * 꺼진 날에도 이 숫자가 올라가야 한다. *"판정할 수 없는 미션이었다"* 와 섞이지만, 둘 다
 * **AI 가 실질적으로 판단하지 않았다**는 점에서 같고, 운영이 봐야 하는 것도 그쪽이다.
 */
private fun MissionVerdict.isFailOpen(): Boolean =
    outcome == com.lwg.challenge.domain.verification.VerdictOutcome.ERROR ||
        outcome == com.lwg.challenge.domain.verification.VerdictOutcome.UNVERIFIABLE

/**
 * 실행 1회의 집계. 로그와 테스트가 **숫자로** 검증하는 값이다 (`JudgementRunResult` 선례).
 *
 * @param verified AI 가 **실제로 판단한** 건수 (`PASS`/`REJECT`/`UNCERTAIN`).
 *   ⚠️ [failed] 와 배타적이다 — 판단하지 못한 건(`ERROR`/`UNVERIFIABLE`)은 이쪽에 안 들어온다.
 *   `verified + rejected` 가 아니라 **`verified` 안에 `rejected` 가 포함**된다
 * @param rejected 그중 반려로 `FAILED` 전이시킨 건수. 🔴 **이 숫자만큼 사람이 진다** —
 *   되돌릴 경로가 없으므로 갑자기 튀면 그날 `ai_reason` 을 읽어봐야 한다
 * @param skipped `ai_checked_at` 이 이미 있어 호출하지 않은 건수. 0 이 아니면 배치가 겹쳤거나
 *   앞선 실행이 남긴 건이다
 * @param failed 🔴 **AI 판별이 되지 않아 통과시킨 건수 (fail-open).** ADR-0013 §결정 4 가
 *   *"배치 요약에 숫자로 남겨라"* 라고 요구한 바로 그 값이다.
 *
 *   **0 이 아닌 날이 있으면 그날 fail-open 이 돌았다는 뜻이고, 가장 흔한 원인은 선불 잔액
 *   소진 또는 12개월 만료(`429 RESOURCE_EXHAUSTED`)다.** 그 상태는 조용하다 — 판정은 정상적으로
 *   나가고 에러도 사용자 불만도 없이 **아무 사진이나 이기는 상태로 되돌아가 있다.**
 *   🔴 이 값을 로그에서 빼지 마라. 로그에 묻어 두면 아무도 안 본다는 것이 ADR 의 지적이고,
 *   이 숫자가 그 지적에 대한 유일한 답이다.
 */
data class VerificationRunResult(
    val verified: Int,
    val rejected: Int,
    val skipped: Int,
    val failed: Int,
) {
    /** 아무 일도 일어나지 않았는가. 로그를 남길지 정하는 데만 쓴다. */
    fun isEmpty(): Boolean = verified == 0 && rejected == 0 && skipped == 0 && failed == 0
}
