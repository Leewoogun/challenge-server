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
 * 기권한 사람의 **역할** (challenge-forfeit T-B1).
 *
 * ## 🔴 `:service` 의 `VerificationParty` 를 재사용하지 마라
 *
 * 이름도 값도 같지만(`CHALLENGER`/`OPPONENT`) 그건 `VerificationService.kt` 안, 즉 **`:service`
 * 모듈**에 있다. [ChallengeVerdict] 는 `:domain:model` 이라 그걸 참조하는 순간 **도메인이
 * 서비스에 의존하게 되어 방향이 뒤집힌다.** 값이 겹치는 것은 비용이 아니라, 판정 규칙이 인증
 * 서비스의 사정과 무관하게 서 있다는 증거다 — 한쪽이 party 를 늘려도 다른 쪽이 안 흔들린다.
 *
 * ## 🔴 `userId` 가 아니라 역할인 이유
 *
 * [ChallengeVerdict] KDoc 의 *"환산 책임을 호출부에 둔다"* 를 그대로 따른다. 이 enum 은
 * `challenges.forfeited_by`(userId) 를 **호출부가 이미 환산한** 결과이고, 이 함수는 환산된
 * 값만 본다. userId 를 받으면 판정 함수가 challengerId/opponentId 까지 알아야 하고,
 * 그 순간 순수 함수가 아니게 된다.
 */
enum class Forfeit { CHALLENGER, OPPONENT }

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
 *
 * ## 🔴 기권은 **여기에 들어왔다** (challenge-forfeit, 2026-09-05)
 *
 * 기권은 위 KDoc 이 경계하던 *"두 번째 호출부"* 그 자체다 — 자정 배치가 아닌 경로에서 승패가
 * 정해진다. 그래서 [decide] 에 [Forfeit] 파라미터를 **늘렸다.** 시그니처가 넓어지는 대가를
 * 치른 이유는, 그 대가를 치르는 자리가 **규칙이 사는 이 한 곳**이라 사본이 생기지 않기 때문이다.
 * 기권 판정을 서비스 쪽 `when` 으로 빼면 그 순간 배치와 기권이 서로 다른 규칙을 갖는다.
 */
object ChallengeVerdict {

    /**
     * 기권 여부와 두 인증 여부로 역할 기준 결과를 정한다.
     *
     * ## 🔴 기권이 **가장 먼저** 걸린다 — 인증 여부를 보지 않는다
     *
     * | forfeit | challenger | opponent | result |
     * |---|---|---|---|
     * | [Forfeit.CHALLENGER] | **무관** | **무관** | [ChallengeResult.OPPONENT_WIN] |
     * | [Forfeit.OPPONENT] | **무관** | **무관** | [ChallengeResult.CHALLENGER_WIN] |
     * | `null` | 인증 | 인증 | [ChallengeResult.DRAW] |
     * | `null` | 인증 | 미인증 | [ChallengeResult.CHALLENGER_WIN] |
     * | `null` | 미인증 | 인증 | [ChallengeResult.OPPONENT_WIN] |
     * | `null` | 미인증 | 미인증 | [ChallengeResult.BOTH_LOSE] |
     *
     * 🔑 **앞 두 행의 "무관" 이 이 feature 의 전부다.** 기권자와 **상대가 둘 다 미인증**이어도
     * 상대가 이긴다 — 아래 4행의 규칙만 있었다면 그 자리는 [ChallengeResult.BOTH_LOSE] 였다.
     * 사용자 결정(2026-09-05): 기권은 *"나는 인증 안 한 것으로 친다"* 가 아니라
     * **"상대에게 승리를 준다"** 는 선언이다. 그래서 인증 상태보다 위에 선다.
     *
     * 🔴 **뒤 4행은 한 글자도 바뀌지 않았다** (기획 §2.6). 기권은 기존 규칙을 고친 것이 아니라
     * 그 **앞에 붙은 별도 규칙**이다. 4행을 손대는 변경은 이 feature 의 범위가 아니다.
     *
     * 🔴 **양측 인증이 `DRAW` 인 것은 의도다.** 둘 다 미션을 지켰는데 한쪽을 이기게 만들려면
     * 인증 시각·사진 품질 같은 서버가 판단할 수 없는 기준이 필요하다. 기획 원안(§2.6) 그대로
     * 무승부로 둔다.
     *
     * @param forfeit 기권한 **역할**. `null` = **기권 없음**(자정 배치가 부르는 정상 경로).
     *   기본값을 주지 않은 것은 의도다 — 기본값이 있으면 새 호출부가 기권 판정을 **조용히
     *   빠뜨린 채** 컴파일된다. 넘기기 싫어도 `null` 을 명시하게 만든다.
     */
    fun decide(
        challengerVerified: Boolean,
        opponentVerified: Boolean,
        forfeit: Forfeit?,
    ): ChallengeResult = when {
        // 🔴 인증 여부보다 먼저다. 순서를 내리면 "상대도 미인증인데 상대 승" 이 깨진다.
        forfeit == Forfeit.CHALLENGER -> ChallengeResult.OPPONENT_WIN
        forfeit == Forfeit.OPPONENT -> ChallengeResult.CHALLENGER_WIN
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
