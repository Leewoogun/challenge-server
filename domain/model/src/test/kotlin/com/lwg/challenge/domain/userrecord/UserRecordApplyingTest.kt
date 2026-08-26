package com.lwg.challenge.domain.userrecord

import com.lwg.challenge.domain.challenge.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * [applying] 단위 테스트 (challenge-result T-B3, 계약 §3.3).
 *
 * 🔴 **핵심은 `currentStreak` 이 연승 전용이라는 것** — 승리만 잇고 패·무·양패는 전부 0 으로
 * 끊는다. 모바일 `UserRecord` KDoc 과 `HomeUiState` 의 `"N🔥"` 표기가 이미 전제하는 정의라
 * 서버가 그걸 이행하는 것이고, 어긋나면 홈 화면의 숫자가 조용히 틀린다.
 *
 * 🔴 **`currentLossStreak`(V10) 은 그 정확한 거울상이다** — 패배(`LOSE`/`BOTH_LOSE`)만 잇고
 * 승리와 **무승부**가 0 으로 끊는다. 아직 읽는 화면이 없어서 회귀가 화면에 안 드러나므로,
 * 규칙을 고정하는 곳이 여기밖에 없다.
 *
 * `updatedAt` 은 인자로 받으므로 여기서 `Clock` 이 필요 없다 — 순수 함수의 이점 그대로다.
 */
class UserRecordApplyingTest {

    private val at = LocalDateTime.of(2026, 8, 25, 0, 5, 0)
    private val later = LocalDateTime.of(2026, 8, 26, 0, 5, 0)

    private fun empty() = UserRecord.empty(USER_ID)

    private companion object {
        const val USER_ID = 1L
    }

    // ─── 기본 집계 ───────────────────────────────────────────────

    @Test
    fun `빈 전적에 첫 승을 반영하면 total 과 wins 와 streak 이 모두 1 이 된다`() {
        val result = empty().applying(Outcome.WIN, at)

        assertEquals(1, result.totalChallenges)
        assertEquals(1, result.wins)
        assertEquals(0, result.losses)
        assertEquals(0, result.draws)
        assertEquals(1, result.currentStreak)
        assertEquals(1, result.maxStreak)
        assertEquals(at, result.updatedAt)
    }

    /** 계약 §3.3: *"`total_challenges` 는 양측 모두 +1 (네 경우 전부)"*. */
    @Test
    fun `total_challenges 는 결과 종류와 무관하게 항상 1 씩 오른다`() {
        Outcome.entries.forEach { outcome ->
            assertEquals(1, empty().applying(outcome, at).totalChallenges, "outcome=$outcome")
        }
    }

    @Test
    fun `패배는 losses 만 올린다`() {
        val result = empty().applying(Outcome.LOSE, at)

        assertEquals(1, result.totalChallenges)
        assertEquals(0, result.wins)
        assertEquals(1, result.losses)
        assertEquals(0, result.draws)
    }

    @Test
    fun `무승부는 draws 만 올린다`() {
        val result = empty().applying(Outcome.DRAW, at)

        assertEquals(1, result.totalChallenges)
        assertEquals(0, result.wins)
        assertEquals(0, result.losses)
        assertEquals(1, result.draws)
    }

    // ─── 🔴 streak — 연승 전용 ──────────────────────────────────

    @Test
    fun `연승은 누적되고 maxStreak 이 따라 오른다`() {
        val first = empty().applying(Outcome.WIN, at)
        val second = first.applying(Outcome.WIN, at)
        val third = second.applying(Outcome.WIN, at)

        assertEquals(1, first.currentStreak)
        assertEquals(2, second.currentStreak)
        assertEquals(3, third.currentStreak)
        assertEquals(3, third.maxStreak)
        assertEquals(3, third.wins)
    }

    @Test
    fun `패배는 연승을 0 으로 끊는다`() {
        val streak = empty().applying(Outcome.WIN, at).applying(Outcome.WIN, at)

        assertEquals(0, streak.applying(Outcome.LOSE, at).currentStreak)
    }

    /**
     * 🔴 spec 오픈 이슈였던 지점이다. **무승부는 연승을 잇지 않는다** — 계약 §3.3 이
     * 모바일 KDoc(*"lose/draw/both_lose 시 0"*)을 따라 확정했다.
     */
    @Test
    fun `무승부도 연승을 0 으로 끊는다`() {
        val streak = empty().applying(Outcome.WIN, at).applying(Outcome.WIN, at)

        assertEquals(0, streak.applying(Outcome.DRAW, at).currentStreak)
    }

    /**
     * 양측 패는 [Outcome.BOTH_LOSE] 로 **접히지 않고 그대로** 들어온다
     * (`ChallengeVerdict.outcomeFor`). 별개의 enum 값이 됐으므로 `when` 에서 빠뜨리면
     * 연승이 끊기지 않고 이어지는데, 그러면 아무도 미션을 지키지 않은 챌린지가 🔥 를 늘린다.
     */
    @Test
    fun `양측 패도 연승을 0 으로 끊는다`() {
        val streak = empty().applying(Outcome.WIN, at)

        assertEquals(0, streak.applying(Outcome.BOTH_LOSE, at).currentStreak)
    }

    /**
     * 🔴 `maxStreak` 은 **줄지 않는다.** `max` 로 감싸지 않으면 연승이 끊길 때 0 으로
     * 되돌아가는데, 그러면 "최대 연승" 이 "현재 연승" 과 같은 값이 되어 컬럼이 무의미해진다.
     */
    @Test
    fun `연승이 끊겨도 maxStreak 은 줄지 않는다`() {
        val peak = empty().applying(Outcome.WIN, at).applying(Outcome.WIN, at).applying(Outcome.WIN, at)

        val afterLoss = peak.applying(Outcome.LOSE, at)
        val afterDraw = afterLoss.applying(Outcome.DRAW, at)

        assertEquals(3, afterLoss.maxStreak)
        assertEquals(3, afterDraw.maxStreak)
        assertEquals(0, afterDraw.currentStreak)
    }

    @Test
    fun `끊긴 뒤 다시 이겨도 이전 최고치를 넘기 전까지 maxStreak 은 그대로다`() {
        val peak = empty().applying(Outcome.WIN, at).applying(Outcome.WIN, at).applying(Outcome.WIN, at)

        val restarted = peak.applying(Outcome.LOSE, at).applying(Outcome.WIN, at)

        assertEquals(1, restarted.currentStreak)
        assertEquals(3, restarted.maxStreak, "1 연승이 3 연승 기록을 덮어쓰면 안 된다")
    }

    @Test
    fun `이전 최고치를 넘어서면 maxStreak 이 갱신된다`() {
        var record = empty()
        repeat(4) { record = record.applying(Outcome.WIN, at) }

        assertEquals(4, record.currentStreak)
        assertEquals(4, record.maxStreak)
    }

    // ─── 🔴 lossStreak — 연패 전용 (V10) ────────────────────────

    @Test
    fun `연패는 누적되고 maxLossStreak 이 따라 오른다`() {
        val first = empty().applying(Outcome.LOSE, at)
        val second = first.applying(Outcome.LOSE, at)
        val third = second.applying(Outcome.LOSE, at)

        assertEquals(1, first.currentLossStreak)
        assertEquals(2, second.currentLossStreak)
        assertEquals(3, third.currentLossStreak)
        assertEquals(3, third.maxLossStreak)
        assertEquals(3, third.losses)
    }

    /**
     * 🔴 양측 패는 **패배다.** `losses` 와 `current_loss_streak` 이 서로 다른 사건 집합을 세면
     * 개돼지 랭킹(기획 §3.4)의 두 정렬 축(총 패배 수·연패)이 어긋난다. home-feed 정본이
     * *"BOTH_LOSE → 양쪽 lose+1"* 로 확정했다 (계약 §3.3).
     */
    @Test
    fun `양측 패는 연패를 잇고 losses 도 올린다`() {
        val first = empty().applying(Outcome.BOTH_LOSE, at)

        assertEquals(1, first.losses, "양측 패가 losses 에 안 세어지면 아무 데도 안 세어진다")
        assertEquals(1, first.currentLossStreak)

        val second = first.applying(Outcome.LOSE, at)
        assertEquals(2, second.losses)
        assertEquals(2, second.currentLossStreak, "LOSE 와 BOTH_LOSE 는 같은 연패를 잇는다")
    }

    @Test
    fun `승리는 연패를 0 으로 끊는다`() {
        val slump = empty().applying(Outcome.LOSE, at).applying(Outcome.BOTH_LOSE, at)

        assertEquals(0, slump.applying(Outcome.WIN, at).currentLossStreak)
    }

    /**
     * 🔴 **이 규칙의 핵심 단언이다.** 무승부는 패배가 아니므로 "연속 패배" 가 이어질 근거가 없다.
     * 연승 쪽에서 무승부가 연승을 끊는 것과 정확한 거울상이라, 한쪽만 보고 나머지를 추측하는
     * 다음 사람이 맞게 추측한다.
     */
    @Test
    fun `무승부는 연패를 0 으로 끊는다`() {
        val slump = empty().applying(Outcome.LOSE, at).applying(Outcome.LOSE, at)

        assertEquals(2, slump.currentLossStreak)
        assertEquals(0, slump.applying(Outcome.DRAW, at).currentLossStreak)
    }

    /** `maxStreak` 과 같은 이유 — `max` 로 감싸지 않으면 연패가 끊길 때 0 으로 되돌아간다. */
    @Test
    fun `연패가 끊겨도 maxLossStreak 은 줄지 않는다`() {
        val bottom = empty()
            .applying(Outcome.LOSE, at)
            .applying(Outcome.LOSE, at)
            .applying(Outcome.BOTH_LOSE, at)

        val afterWin = bottom.applying(Outcome.WIN, at)
        val afterDraw = afterWin.applying(Outcome.DRAW, at)

        assertEquals(3, afterWin.maxLossStreak)
        assertEquals(3, afterDraw.maxLossStreak)
        assertEquals(0, afterDraw.currentLossStreak)
    }

    /**
     * 🔴 두 streak 은 **동시에 살아 있을 수 없다.** 한쪽만 갱신하고 다른 쪽을 0 으로 끊는 것을
     * 잊으면 "3연승이면서 동시에 2연패" 같은 상태가 만들어지고, 랭킹과 홈이 서로 다른 얘기를 한다.
     */
    @Test
    fun `연승과 연패가 동시에 살아 있지 않다`() {
        val afterWin = empty().applying(Outcome.LOSE, at).applying(Outcome.WIN, at)
        assertTrue(afterWin.currentStreak > 0)
        assertEquals(0, afterWin.currentLossStreak)

        val afterLoss = afterWin.applying(Outcome.LOSE, at)
        assertEquals(0, afterLoss.currentStreak)
        assertTrue(afterLoss.currentLossStreak > 0)

        val afterBothLose = afterLoss.applying(Outcome.BOTH_LOSE, at)
        assertEquals(0, afterBothLose.currentStreak)
        assertTrue(afterBothLose.currentLossStreak > 0)

        val afterDraw = afterBothLose.applying(Outcome.DRAW, at)
        assertEquals(0, afterDraw.currentStreak, "무승부는 양쪽 다 끊는다")
        assertEquals(0, afterDraw.currentLossStreak)
    }

    // ─── 불변성 / 시각 ───────────────────────────────────────────

    @Test
    fun `updatedAt 은 넘긴 시각으로 갱신된다`() {
        val first = empty().applying(Outcome.WIN, at)

        assertEquals(later, first.applying(Outcome.LOSE, later).updatedAt)
    }

    /** 원본을 변형하지 않는다 — 판정 트랜잭션이 롤백돼도 메모리상 값이 남지 않아야 한다. */
    @Test
    fun `원본 전적은 변하지 않는다`() {
        val original = empty()

        original.applying(Outcome.WIN, at)

        assertEquals(0, original.totalChallenges)
        assertEquals(0, original.wins)
        assertEquals(0, original.currentStreak)
    }

    /** `userId` 는 집계 대상이 아니라 신원이다 — 어떤 경우에도 바뀌지 않는다. */
    @Test
    fun `userId 는 유지된다`() {
        assertEquals(USER_ID, empty().applying(Outcome.WIN, at).userId)
    }
}
