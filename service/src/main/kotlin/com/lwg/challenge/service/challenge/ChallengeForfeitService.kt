package com.lwg.challenge.service.challenge

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeResult
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.ChallengeVerdict
import com.lwg.challenge.domain.challenge.Outcome
import com.lwg.challenge.domain.common.exception.OneButtonDialogException
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.notification.NotificationType
import com.lwg.challenge.domain.verification.VerificationRepository
import com.lwg.challenge.domain.verification.VerificationStatus
import com.lwg.challenge.service.notification.ChallengeNotificationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 챌린지 기권 — `POST /challenges/{id}/forfeit` (challenge-forfeit T-B3).
 *
 * ## 기권이란 무엇인가 (사용자 결정 2026-09-05)
 *
 * 🔴 *"나는 인증 안 한 것으로 친다"* 가 **아니라** **"상대에게 승리를 준다"** 는 선언이다.
 * 그래서 **상대가 미인증이어도 상대가 이긴다** — 기존 규칙만 있었다면 `BOTH_LOSE` 였을 자리다.
 * 그 규칙은 여기가 아니라 [ChallengeVerdict.decide] 안에 있다.
 *
 * ## 🔴 전적 집계를 새로 쓰지 않는다 — [ChallengeJudgementService.judge] 를 그대로 부른다
 *
 * 이 서비스가 하는 일은 **검증 3종 + `forfeited_by` 기록**뿐이고, 판정·`COMPLETED` 전이·
 * `user_stats`·`friend_records` 는 전부 `judge()` 가 한다. 자정 배치가 부르는 바로 그 함수다.
 *
 * 여기서 집계를 새로 쓰면 **기권 경로와 배치 경로가 두 벌의 규칙을 갖는다** —
 * [ChallengeVerdict] KDoc 이 *"배치가 매긴 결과와 화면이 계산한 결과가 다르면 컴파일러도
 * 테스트도 잡아주지 못하는 종류의 버그"* 라며 정확히 경계하는 지점이다.
 * `judge()` 는 `challenges.forfeited_by` 를 읽어 스스로 [com.lwg.challenge.domain.challenge.Forfeit]
 * 으로 환산하므로, 이 서비스는 **판정에 대해 아무것도 모른다.**
 *
 * ## 🔴 알림은 여기서 발행한다 — `judge()` 가 아니라
 *
 * [ChallengeJudgementService] 는 생성자에 `ApplicationEventPublisher` 가 **없는 것 자체가**
 * *"배치는 알림을 보내지 않는다"* 의 보장이다(그 KDoc). 기권 알림을 그쪽에 넣으면 그 보장이
 * 깨지고 **자정 배치가 판정한 기권 건까지 알림을 쏘게 된다.** 그래서 발행 지점이 이 서비스다.
 *
 * ## 🔴 왜 잠그고 시작하는가
 *
 * 기권은 **양쪽 당사자 모두가 부를 수 있는 유일한 상태 전이**다(수락·거절은 opponent, 취소는
 * challenger 만 부른다). 잠금이 없으면 동시에 누른 두 요청이 **둘 다** `IN_PROGRESS` 를 읽고
 * 통과해 전적이 두 번 세어진다 — `total_challenges` 는 소급 정정이 불가능하다.
 * `findByIdForUpdate` 가 두 번째를 첫 커밋 뒤로 밀면, 그때 status 가 `COMPLETED` 라
 * **아래의 평범한 `IN_PROGRESS` 게이트가 그대로 막는다.** 동시성 전용 규칙이 없다는 것이 핵심이다.
 *
 * ## 에러 코드 — `ChallengeCommandService` / `VerificationService` 배분을 그대로 따른다
 *
 * - **700 (스낵바)** — 권한 위반
 * - **705 (단일버튼 다이얼로그)** — *"네 화면이 이미 낡았다"* (없음 / 상태가 다름 / 이미 인증함)
 *
 * ⚠️ 계약 초안은 4가지를 전부 700 으로 적었으나 **705 로 고쳤다** (계약 §4 협의 이력).
 * 초안대로면 `"챌린지를 찾을 수 없어요"` 라는 **같은 문장**이 상세·취소·인증에서는 다이얼로그,
 * 기권에서만 스낵바로 떠서 앱이 같은 사실을 두 가지로 그린다.
 * 특히 *"이미 인증함"* 은 사용자가 고칠 수 있는 것이 아니다 — 인증을 무를 수 없으므로,
 * 이 응답이 나갔다는 건 **기권 진입점이 남아 있는 낡은 화면**이라는 뜻이고 705 가 맞다.
 *
 * 모바일은 `message` 를 그대로 노출하므로 **여기 문자열은 전부 UI 텍스트다.**
 */
@Service
class ChallengeForfeitService(
    private val challengeRepository: ChallengeRepository,
    private val verificationRepository: VerificationRepository,
    private val judgementService: ChallengeJudgementService,
    /**
     * 기권 알림(`CHALLENGE_FORFEITED`) 발행. **발송은 커밋 후**다 —
     * `NotificationDispatcher` 가 `AFTER_COMMIT` 으로 받는다. 롤백되면 커밋이 없어
     * 리스너가 아예 실행되지 않으므로 **미발송이 공짜로 성립한다.**
     */
    private val eventPublisher: ApplicationEventPublisher,
) {

    /**
     * 기권한다. 성공하면 챌린지가 **그 자리에서** `COMPLETED` 가 되고 전적까지 반영된다.
     *
     * 순서는 계약 §1 의 "서버 내부 순서" 그대로다:
     * 1. 잠금 조회 → 검증 3종 (당사자 / `IN_PROGRESS` / 내 인증 없음)
     * 2. `forfeited_by = me`
     * 3. [ChallengeJudgementService.judge] — 배치와 **같은 함수**
     * 4. `CHALLENGE_FORFEITED` 이벤트 (커밋 후 발송)
     *
     * 🔴 **멱등이 아니다.** 두 번째 요청은 `COMPLETED` 라 2단계 게이트에서 거부된다.
     *
     * 🔴 **검사 순서가 의미를 만든다** — 권한을 상태보다 먼저 본다. 당사자가 아닌 사람에게
     * *"진행 중인 챌린지가 아니에요"* 라고 답하면 남의 챌린지 상태가 새어나간다
     * (`ChallengeCommandService.loadForResponse` 와 같은 이유).
     *
     * @return 확정된 결과. `myResult` 는 [ChallengeVerdict.outcomeFor] 를 실제로 태운 값이다 —
     *   오늘 항상 [Outcome.LOSE] 인 것은 **결과이지 하드코딩이 아니다.**
     */
    @Transactional
    fun forfeit(me: Long, challengeId: Long): ForfeitView {
        // 🔴 트랜잭션의 **첫 문장**이어야 한다 (잠금 순서 고정 — `findByIdForUpdate` KDoc).
        val challenge = challengeRepository.findByIdForUpdate(challengeId)
            ?: throw OneButtonDialogException(MSG_NOT_FOUND)

        if (me != challenge.challengerId && me != challenge.opponentId) {
            throw SnackbarException(MSG_NOT_MINE)
        }
        if (challenge.status != ChallengeStatus.IN_PROGRESS) {
            throw OneButtonDialogException(MSG_NOT_IN_PROGRESS)
        }
        if (isVerified(challengeId, me)) {
            throw OneButtonDialogException(MSG_ALREADY_VERIFIED)
        }

        challengeRepository.save(challenge.copy(forfeitedBy = me))

        // 🔴 반환값을 무시하지 않는다. false 는 "판정 대상이 아니었다" 인데, 방금 잠근 채로
        //    IN_PROGRESS 를 확인했으므로 그럴 수 없다. 그런데도 false 라면 판정이 통째로
        //    일어나지 않은 것이고, 그대로 200 을 돌려주면 **앱은 끝났다고 믿고 서버는 진행 중**
        //    이라는 갈라짐이 조용히 남는다.
        check(judgementService.judge(challengeId)) {
            "기권 대상이 판정되지 않았다 (challengeId=$challengeId, me=$me)"
        }

        val judged = challengeRepository.findById(challengeId)
            ?: error("방금 판정한 challenge 를 다시 읽지 못했다 (challengeId=$challengeId)")
        val result = judged.result
            ?: error("판정된 challenge 의 result 가 null 일 수 없다 (challengeId=$challengeId)")

        eventPublisher.publishEvent(
            ChallengeNotificationEvent(
                // 알릴 대상은 **기권당한 쪽(= 이긴 쪽)**이다. 기권한 본인이 아니다.
                recipientId = opponentOf(judged, me),
                actorId = me,
                type = NotificationType.CHALLENGE_FORFEITED,
                challengeId = challengeId,
            ),
        )

        return ForfeitView(
            challengeId = challengeId,
            status = judged.status,
            result = result,
            // 🔴 "기권했으니 LOSE" 를 여기서 쓰지 않는다. 판정 규칙 함수를 태운다 —
            //    그래야 이 값이 result 와 어긋날 수 없다.
            myResult = ChallengeVerdict.outcomeFor(result, isChallenger = me == judged.challengerId),
            completedAt = judged.completedAt
                ?: error("판정된 challenge 의 completedAt 이 null 일 수 없다 (challengeId=$challengeId)"),
        )
    }

    /**
     * 내가 이미 인증했는가. **row 부재는 미인증**이다 ([ChallengeVerdict] KDoc 의 미인증 정의).
     *
     * ⚠️ `VERIFIED` 만 인증이다 — `PENDING`/`FAILED` 는 기권할 수 있다.
     */
    private fun isVerified(challengeId: Long, me: Long): Boolean =
        verificationRepository.findAllByChallengeIds(listOf(challengeId))
            .firstOrNull { it.userId == me }
            ?.status == VerificationStatus.VERIFIED

    private fun opponentOf(challenge: Challenge, me: Long): Long =
        if (me == challenge.challengerId) challenge.opponentId else challenge.challengerId

    companion object {
        // ── 사용자 노출 문구 (api-contract 확정. 변경 시 계약 + 테스트 동시 수정) ──
        //
        // 앞의 둘은 기존 서비스와 **글자까지 같다** — 같은 사실을 화면마다 다른 문장으로
        // 말하지 않기 위해서다 (`ChallengeDetailService` / `VerificationService`).
        const val MSG_NOT_FOUND = "챌린지를 찾을 수 없어요"
        const val MSG_NOT_MINE = "내 챌린지가 아니에요"
        const val MSG_NOT_IN_PROGRESS = "진행 중인 챌린지만 기권할 수 있어요"
        const val MSG_ALREADY_VERIFIED = "이미 인증해서 기권할 수 없어요"
    }
}

/**
 * 기권 결과. 응답 DTO 는 `:controller` 가 만든다.
 *
 * @param status 항상 [ChallengeStatus.COMPLETED] — 기권은 즉시 확정이다. 그래도 상수로 박지 않고
 *   판정된 row 에서 읽는다. 서버가 응답으로 주장하는 것과 DB 에 있는 것이 갈릴 자리를 없앤다.
 * @param result **역할 기준.** 기권자가 challenger 면 `OPPONENT_WIN`, opponent 면 `CHALLENGER_WIN`.
 * @param myResult **요청자(= 기권자) 시점.** 오늘 항상 [Outcome.LOSE] 지만
 *   [ChallengeVerdict.outcomeFor] 를 태운 값이다.
 * @param completedAt 기권 시각이기도 하다 — 기권은 즉시 `COMPLETED` 라 별도 컬럼이 없다(V12).
 */
data class ForfeitView(
    val challengeId: Long,
    val status: ChallengeStatus,
    val result: ChallengeResult,
    val myResult: Outcome,
    val completedAt: LocalDateTime,
)
