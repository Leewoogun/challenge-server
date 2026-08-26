package com.lwg.challenge.domain.challenge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [ChallengeVerdict] 단위 테스트 (challenge-result T-B2, 계약 §3.1).
 *
 * 판정 규칙은 **화면에 뜨는 승패의 정의**라서 표 전체를 빠짐없이 고정한다 —
 * `decide` 4조합 + `outcomeFor` 8조합(4 result × isChallenger 2). 조합이 적으므로 샘플링하지
 * 않고 전수로 간다. 여기가 깨지면 전적이 통째로 틀리는데, 전적은 소급 정정이 불가능하다.
 */
class ChallengeVerdictTest {

    // ─── decide — 계약 §3.1 규칙표 4행 ───────────────────────────

    @Test
    fun `양측 인증은 무승부다`() {
        assertEquals(
            ChallengeResult.DRAW,
            ChallengeVerdict.decide(challengerVerified = true, opponentVerified = true),
        )
    }

    @Test
    fun `챌린저만 인증하면 챌린저 승이다`() {
        assertEquals(
            ChallengeResult.CHALLENGER_WIN,
            ChallengeVerdict.decide(challengerVerified = true, opponentVerified = false),
        )
    }

    @Test
    fun `상대만 인증하면 상대 승이다`() {
        assertEquals(
            ChallengeResult.OPPONENT_WIN,
            ChallengeVerdict.decide(challengerVerified = false, opponentVerified = true),
        )
    }

    @Test
    fun `양측 미인증은 양측 패다`() {
        assertEquals(
            ChallengeResult.BOTH_LOSE,
            ChallengeVerdict.decide(challengerVerified = false, opponentVerified = false),
        )
    }

    // ─── outcomeFor — 4 result × 2 역할 = 8조합 ──────────────────

    @Test
    fun `CHALLENGER_WIN 은 챌린저에게 승 상대에게 패다`() {
        assertEquals(Outcome.WIN, ChallengeVerdict.outcomeFor(ChallengeResult.CHALLENGER_WIN, isChallenger = true))
        assertEquals(Outcome.LOSE, ChallengeVerdict.outcomeFor(ChallengeResult.CHALLENGER_WIN, isChallenger = false))
    }

    @Test
    fun `OPPONENT_WIN 은 챌린저에게 패 상대에게 승이다`() {
        assertEquals(Outcome.LOSE, ChallengeVerdict.outcomeFor(ChallengeResult.OPPONENT_WIN, isChallenger = true))
        assertEquals(Outcome.WIN, ChallengeVerdict.outcomeFor(ChallengeResult.OPPONENT_WIN, isChallenger = false))
    }

    @Test
    fun `DRAW 는 양쪽 다 무승부다`() {
        assertEquals(Outcome.DRAW, ChallengeVerdict.outcomeFor(ChallengeResult.DRAW, isChallenger = true))
        assertEquals(Outcome.DRAW, ChallengeVerdict.outcomeFor(ChallengeResult.DRAW, isChallenger = false))
    }

    /**
     * 🔴 **`LOSE` 로 접지 않는다** (모바일 협의 확정 — [Outcome] KDoc).
     *
     * 접으면 앱이 "둘 다 못 했다" 를 인증 상태 두 개로 역산해야 하고, 그게 판정 규칙의 두 번째
     * 사본이다. 관점 중립인 값이라 양쪽에서 뜻이 같고, 그래서 뒤집지 않아도 "내 시점" 축이
     * 깨지지 않는다. `isChallenger` 를 보지 않는 유일한 값이라는 점을 두 단언으로 고정한다.
     */
    @Test
    fun `BOTH_LOSE 는 양쪽 다 BOTH_LOSE 다`() {
        assertEquals(Outcome.BOTH_LOSE, ChallengeVerdict.outcomeFor(ChallengeResult.BOTH_LOSE, isChallenger = true))
        assertEquals(Outcome.BOTH_LOSE, ChallengeVerdict.outcomeFor(ChallengeResult.BOTH_LOSE, isChallenger = false))
    }

    /**
     * 🔴 승자는 항상 **정확히 한 명**이거나 없다. 어떤 result 도 양쪽을 동시에 이기게 하지 않는다.
     *
     * 위 8조합을 다른 축에서 한 번 더 묶는 것이라 중복처럼 보이지만, `ChallengeResult` 에 값이
     * 추가되는 날 개별 테스트는 그대로 초록인 채 이 테스트만 깨진다 — 그게 이 테스트의 값이다.
     */
    @Test
    fun `어떤 결과에서도 양쪽이 동시에 이기지는 않는다`() {
        ChallengeResult.entries.forEach { result ->
            val winners = listOf(true, false)
                .map { ChallengeVerdict.outcomeFor(result, isChallenger = it) }
                .count { it == Outcome.WIN }

            // 무승부와 양측 패는 승자가 0 명, 나머지는 1 명이다.
            val expected = if (result == ChallengeResult.DRAW || result == ChallengeResult.BOTH_LOSE) 0 else 1
            assertEquals(expected, winners, "result=$result")
        }
    }
}
