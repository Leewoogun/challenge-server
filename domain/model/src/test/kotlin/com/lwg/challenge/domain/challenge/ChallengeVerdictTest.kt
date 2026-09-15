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
            ChallengeVerdict.decide(challengerVerified = true, opponentVerified = true, forfeit = null),
        )
    }

    @Test
    fun `챌린저만 인증하면 챌린저 승이다`() {
        assertEquals(
            ChallengeResult.CHALLENGER_WIN,
            ChallengeVerdict.decide(challengerVerified = true, opponentVerified = false, forfeit = null),
        )
    }

    @Test
    fun `상대만 인증하면 상대 승이다`() {
        assertEquals(
            ChallengeResult.OPPONENT_WIN,
            ChallengeVerdict.decide(challengerVerified = false, opponentVerified = true, forfeit = null),
        )
    }

    @Test
    fun `양측 미인증은 양측 패다`() {
        assertEquals(
            ChallengeResult.BOTH_LOSE,
            ChallengeVerdict.decide(challengerVerified = false, opponentVerified = false, forfeit = null),
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

    // ─── decide — 기권 (challenge-forfeit T-B1, spec §1.1) ───────

    /**
     * 🔴 **이 테스트가 이 feature 의 결정을 지키는 단언이다** (spec §3).
     *
     * 기권자와 상대가 **둘 다 미인증**인 조합이다. 기권 규칙이 없었다면
     * [ChallengeResult.BOTH_LOSE] 가 나올 자리이고, 기권을 *"미인증으로 친다"* 로 정의했어도
     * 역시 `BOTH_LOSE` 였다. **상대 승**이 나오는 것은 사용자 결정(2026-09-05)이
     * *"기권 = 상대에게 승리를 준다"* 였기 때문이다.
     *
     * 여기가 깨지면 기권이 조용히 *"미인증 취급"* 으로 되돌아간 것이고, **전적은 소급 정정이
     * 불가능하다.** 다른 어떤 테스트도 이걸 대신 잡아주지 못한다.
     */
    @Test
    fun `기권했는데 상대도 미인증이어도 상대가 이긴다`() {
        assertEquals(
            ChallengeResult.OPPONENT_WIN,
            ChallengeVerdict.decide(
                challengerVerified = false,
                opponentVerified = false,
                forfeit = Forfeit.CHALLENGER,
            ),
            "챌린저 기권 + 양측 미인증 → 기권 규칙이 없으면 BOTH_LOSE 가 나오는 자리다",
        )
        assertEquals(
            ChallengeResult.CHALLENGER_WIN,
            ChallengeVerdict.decide(
                challengerVerified = false,
                opponentVerified = false,
                forfeit = Forfeit.OPPONENT,
            ),
            "상대 기권 + 양측 미인증 → 같은 자리를 반대 방향으로 고정한다",
        )
    }

    /**
     * 기권은 **인증 여부를 보지 않는다.** 4조합 전수로 고정한다.
     *
     * 특히 `기권자 본인이 인증한` 조합까지 포함한다 — 서비스가 그걸 거부하므로 정상 경로에서는
     * 도달하지 않지만, **판정 함수 자체는 그 입력에서도 흔들리지 않아야 한다.** 규칙이
     * 입력 조합에 따라 갈리기 시작하면 그게 두 번째 규칙이다.
     */
    @Test
    fun `기권이 있으면 양측 인증 여부와 무관하게 상대가 이긴다`() {
        listOf(true, false).forEach { challengerVerified ->
            listOf(true, false).forEach { opponentVerified ->
                assertEquals(
                    ChallengeResult.OPPONENT_WIN,
                    ChallengeVerdict.decide(challengerVerified, opponentVerified, Forfeit.CHALLENGER),
                    "challenger 기권 (challengerVerified=$challengerVerified, opponentVerified=$opponentVerified)",
                )
                assertEquals(
                    ChallengeResult.CHALLENGER_WIN,
                    ChallengeVerdict.decide(challengerVerified, opponentVerified, Forfeit.OPPONENT),
                    "opponent 기권 (challengerVerified=$challengerVerified, opponentVerified=$opponentVerified)",
                )
            }
        }
    }

    /**
     * 🔴 **기존 4규칙은 한 글자도 안 바뀐다** (spec §1 제외 목록).
     *
     * `forfeit = null` 일 때 위쪽 4개 테스트와 같은 답이 나오는지를 **한 번 더** 묶어서 본다.
     * 개별 테스트가 이미 `null` 로 부르고 있으므로 중복처럼 보이지만, 축이 다르다 —
     * 이건 *"기권 파라미터가 기존 규칙에 아무 영향을 주지 않는다"* 는 진술이다.
     * 누가 기권 분기를 인증 분기 **아래로** 내리면 이 테스트는 그대로 초록이고
     * [기권했는데 상대도 미인증이어도 상대가 이긴다] 만 깨진다 — 둘의 역할이 그렇게 나뉜다.
     */
    @Test
    fun `기권이 없으면 기존 4규칙 그대로다`() {
        val expected = mapOf(
            (true to true) to ChallengeResult.DRAW,
            (true to false) to ChallengeResult.CHALLENGER_WIN,
            (false to true) to ChallengeResult.OPPONENT_WIN,
            (false to false) to ChallengeResult.BOTH_LOSE,
        )
        expected.forEach { (input, result) ->
            val (challengerVerified, opponentVerified) = input
            assertEquals(
                result,
                ChallengeVerdict.decide(challengerVerified, opponentVerified, forfeit = null),
                "challengerVerified=$challengerVerified, opponentVerified=$opponentVerified",
            )
        }
    }

    /**
     * 기권자는 **항상 진다.** [ChallengeVerdict.outcomeFor] 까지 이어 붙여 확인한다.
     *
     * `decide` 만 보면 *"반대편이 이긴다"* 까지이고, 기권자 시점이 [Outcome.LOSE] 인 것은
     * `outcomeFor` 를 한 번 더 태워야 나온다. 응답의 `myResult` 가 그 경로다.
     */
    @Test
    fun `기권자 시점은 항상 패배다`() {
        val challengerForfeited = ChallengeVerdict.decide(false, false, Forfeit.CHALLENGER)
        assertEquals(Outcome.LOSE, ChallengeVerdict.outcomeFor(challengerForfeited, isChallenger = true))
        assertEquals(Outcome.WIN, ChallengeVerdict.outcomeFor(challengerForfeited, isChallenger = false))

        val opponentForfeited = ChallengeVerdict.decide(false, false, Forfeit.OPPONENT)
        assertEquals(Outcome.LOSE, ChallengeVerdict.outcomeFor(opponentForfeited, isChallenger = false))
        assertEquals(Outcome.WIN, ChallengeVerdict.outcomeFor(opponentForfeited, isChallenger = true))
    }

    /** 기권 결과에는 무승부·양패가 **나올 수 없다.** 승자가 반드시 한 명이다. */
    @Test
    fun `기권 결과는 언제나 한쪽 승리다`() {
        Forfeit.entries.forEach { forfeit ->
            listOf(true, false).forEach { challengerVerified ->
                listOf(true, false).forEach { opponentVerified ->
                    val result = ChallengeVerdict.decide(challengerVerified, opponentVerified, forfeit)
                    val winners = listOf(true, false)
                        .map { ChallengeVerdict.outcomeFor(result, isChallenger = it) }
                        .count { it == Outcome.WIN }
                    assertEquals(1, winners, "forfeit=$forfeit result=$result")
                }
            }
        }
    }
}
