package com.lwg.challenge.domain.challenge

/**
 * 한 사람 관점의 결과.
 *
 * [ChallengeResult] 가 **역할 기준**(challenger/opponent)인 데 반해 이쪽은 **"나" 기준**이다.
 * 홈 카드가 소비할 축이자 전적 집계([com.lwg.challenge.domain.userrecord.UserRecord] /
 * [com.lwg.challenge.domain.friendrecord.FriendRecord])의 입력이다.
 *
 * ## 🔴 `BOTH_LOSE` 가 **4번째 값으로 들어와 있다** (모바일 협의로 확정)
 *
 * 초안은 승/패/무 세 값이었고 양측 패를 [LOSE] 로 접었다. 그 접기를 되돌린 이유:
 * 앱이 "둘 다 못 했다" 를 그리려면 `myVerificationStatus == FAILED &&
 * opponentVerificationStatus == FAILED` 로 **역산**해야 복원되는데, 그건
 * **판정 규칙의 두 번째 사본을 앱에 심는 것**이다 — [ChallengeVerdict] 가 *"판정 규칙은 한
 * 곳에만"* 이라며 정확히 경계하는 지점이다.
 *
 * 그러면서도 "내 시점" 좌표계는 깨지지 않는다. [BOTH_LOSE] 는 **관점 중립**이라 challenger 가
 * 봐도 opponent 가 봐도 뜻이 같기 때문이다 — 뒤집을 것이 없는 값이라 뒤집기 축과 충돌하지
 * 않는다. 그게 이 값이 이 enum 에 들어올 자격이다.
 *
 * ⚠️ 그래서 이 enum 을 `when` 으로 소비하는 쪽은 **[BOTH_LOSE] 도 패배로 세야 한다.**
 * `losses` / `current_loss_streak` 가 그렇게 센다 (계약 §3.3).
 */
enum class Outcome { WIN, LOSE, DRAW, BOTH_LOSE }

/**
 * 챌린지 판정 규칙 (기획 §2.6 / 계약 §3.1).
 *
 * ## 🔴 이 프로젝트에서 판정 규칙이 존재하는 **유일한 곳**이다
 *
 * 규칙을 서비스 안 `when` 절로 흩뿌리면, 나중에 재판정·수동 정정·랭킹 재계산 같은 두 번째
 * 호출부가 생기는 순간 **두 벌의 규칙이 갈린다.** 배치가 매긴 결과와 화면이 계산한 결과가
 * 다르면 그건 컴파일러도 테스트도 잡아주지 못하는 종류의 버그다. 그래서 Spring 도 DB 도 모르는
 * 순수 함수 두 개로 못박아 두고, `ChallengeJudgementService` 는 이걸 **호출만** 한다.
 *
 * ## "미인증" 의 정의는 호출부가 아니라 이 KDoc 이 정한다
 *
 * `VerificationStatus.VERIFIED` 가 **아닌 모든 것**이 미인증이다 — `PENDING`, `FAILED`,
 * 그리고 **row 자체가 없는 경우**까지. 여기서 `VerificationStatus` 를 받지 않고 `Boolean` 을
 * 받는 이유가 그것이다: row 부재는 enum 으로 표현할 수 없으므로, 환산 책임을 호출부에 두고
 * 이 함수는 이미 환산된 두 진리값만 본다.
 */
object ChallengeVerdict {

    /**
     * 두 인증 여부로 역할 기준 결과를 정한다.
     *
     * | challenger | opponent | result |
     * |---|---|---|
     * | 인증 | 인증 | [ChallengeResult.DRAW] |
     * | 인증 | 미인증 | [ChallengeResult.CHALLENGER_WIN] |
     * | 미인증 | 인증 | [ChallengeResult.OPPONENT_WIN] |
     * | 미인증 | 미인증 | [ChallengeResult.BOTH_LOSE] |
     *
     * 🔴 **양측 인증이 `DRAW` 인 것은 의도다.** 둘 다 미션을 지켰는데 한쪽을 이기게 만들려면
     * 인증 시각·사진 품질 같은 서버가 판단할 수 없는 기준이 필요하다. 기획 원안(§2.6) 그대로
     * 무승부로 둔다.
     */
    fun decide(challengerVerified: Boolean, opponentVerified: Boolean): ChallengeResult = when {
        challengerVerified && opponentVerified -> ChallengeResult.DRAW
        challengerVerified -> ChallengeResult.CHALLENGER_WIN
        opponentVerified -> ChallengeResult.OPPONENT_WIN
        else -> ChallengeResult.BOTH_LOSE
    }

    /**
     * 역할 기준 결과를 그 사람 관점으로 뒤집는다.
     *
     * @param isChallenger 이 사람이 challenger 인가. false 면 opponent 다.
     *
     * 🔴 [ChallengeResult.BOTH_LOSE] 는 **양쪽 다 [Outcome.BOTH_LOSE]** 로 그대로 나간다.
     * `isChallenger` 를 보지 않는 유일한 값이다 — 관점 중립이라 뒤집을 것이 없다.
     * 접어서 [Outcome.LOSE] 로 내보내지 않는 이유는 [Outcome] KDoc 에 있다(앱이 인증 상태로
     * 역산하게 만들면 판정 규칙이 두 벌이 된다).
     *
     * ⚠️ 그렇다고 이 값이 "패배가 아닌 것" 은 아니다. 전적 집계는 [Outcome.BOTH_LOSE] 도
     * `losses` 로 센다 (계약 §3.3) — 표현만 나뉘고 의미는 패배다.
     */
    fun outcomeFor(result: ChallengeResult, isChallenger: Boolean): Outcome = when (result) {
        ChallengeResult.DRAW -> Outcome.DRAW
        ChallengeResult.BOTH_LOSE -> Outcome.BOTH_LOSE
        ChallengeResult.CHALLENGER_WIN -> if (isChallenger) Outcome.WIN else Outcome.LOSE
        ChallengeResult.OPPONENT_WIN -> if (isChallenger) Outcome.LOSE else Outcome.WIN
    }
}
