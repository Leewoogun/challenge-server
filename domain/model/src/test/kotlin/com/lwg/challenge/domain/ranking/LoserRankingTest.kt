package com.lwg.challenge.domain.ranking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [lossRateOf] / [rankedAsLosers] 단위 테스트 (기획 §3.4 개돼지 랭킹).
 *
 * 🔴 **이 파일이 랭킹 규칙의 유일한 정본이다.** 정렬·순위·패배율은 SQL 이 아니라 순수 함수에
 * 있으므로(`rankedAsLosers` KDoc "왜 SQL ORDER BY 가 아닌가") DB 없이 전부 고정할 수 있고,
 * 반대로 여기서 안 고정하면 아무 데서도 안 고정된다.
 *
 * `UserRecordApplyingTest` 와 같은 이유로 Spring 도 DB 도 쓰지 않는다.
 */
class LoserRankingTest {

    /**
     * 지표 3종만 지정하면 되는 fixture. `nickname` / `profileImageUrl` 은 정렬에 관여하지 않으므로
     * 테스트마다 다르게 쓸 이유가 없다 — 관여하는 값만 인자로 드러낸다.
     */
    private fun stat(
        userId: Long,
        losses: Int = 0,
        totalChallenges: Int = 0,
        currentLossStreak: Int = 0,
    ) = LoserStat(
        userId = userId,
        nickname = "u$userId",
        profileImageUrl = null,
        losses = losses,
        totalChallenges = totalChallenges,
        currentLossStreak = currentLossStreak,
    )

    // ─── lossRateOf ──────────────────────────────────────────────

    /**
     * 🔴 **`null` 이 아니라 `0` 이라는 결정 자체를 고정한다.** 분모가 0 일 때 `null` 을 돌려주면
     * 정렬 키가 null 이 되어 순서 정의가 흔들리고, 앱은 "표시 안 함" 과 "0%" 를 매번 분기해야 한다
     * ([lossRateOf] KDoc). 반환 타입이 `Int?` 로 바뀌는 순간 이 테스트가 컴파일 단계에서 막는다.
     */
    @Test
    fun `0전 0패는 null 이 아니라 0 이다`() {
        assertEquals(0, lossRateOf(losses = 0, totalChallenges = 0))
    }

    @Test
    fun `40전 12패는 30 이다`() {
        assertEquals(30, lossRateOf(losses = 12, totalChallenges = 40))
    }

    /** 78.125 → 78. 소수점 아래가 0.5 미만이면 내려간다. */
    @Test
    fun `32전 25패는 78 이다 - 78 점 125 는 내려간다`() {
        assertEquals(78, lossRateOf(losses = 25, totalChallenges = 32))
    }

    /**
     * 🔴 **HALF_UP 고정.** 12.5 는 `Math.round` 에서 13 이지만 `floor` 로 바꾸면 12 가 된다.
     * 기획/앱이 기대하는 "49.5% → 50%" 가 바로 이 반올림 방향이다 ([lossRateOf] KDoc).
     */
    @Test
    fun `8전 1패는 13 이다 - 12 점 5 는 HALF_UP 으로 올라간다`() {
        assertEquals(13, lossRateOf(losses = 1, totalChallenges = 8))
    }

    @Test
    fun `3전 3패는 100 이다`() {
        assertEquals(100, lossRateOf(losses = 3, totalChallenges = 3))
    }

    /**
     * 위 "0전 0패 → 0" 과 **값은 같지만 사유가 다르다.** 여기는 분모가 멀쩡히 있고 분자가 0 인
     * 정상 나눗셈이라 `if (totalChallenges == 0)` 가드를 지나 실제 계산을 탄다. 가드를 지워도
     * 이 테스트는 통과하고 위 테스트만 깨진다 — 두 경로가 갈라져 있다는 증거다.
     */
    @Test
    fun `5전 0패는 0 이다 - 분모가 있고 분자가 0 인 경우`() {
        assertEquals(0, lossRateOf(losses = 0, totalChallenges = 5))
    }

    // ─── rankedAsLosers — 정렬 키 4개 ────────────────────────────

    /**
     * 1차 키는 `losses` DESC. 뒤쪽 키(연패·패율·userId)를 전부 **반대 방향**으로 세팅해도
     * 패배 수가 많은 쪽이 이긴다 — 그래야 "1차 키" 임이 증명된다.
     */
    @Test
    fun `1차 키는 losses 내림차순이다`() {
        val many = stat(userId = 9L, losses = 10, totalChallenges = 100, currentLossStreak = 0)
        val few = stat(userId = 1L, losses = 3, totalChallenges = 3, currentLossStreak = 9)

        val ranked = listOf(few, many).rankedAsLosers(me = 9L)

        assertEquals(listOf(9L, 1L), ranked.map { it.userId })
    }

    /**
     * 2차 키는 `currentLossStreak` DESC — **`losses` 가 같을 때만** 갈린다.
     * 패율(3차)·userId(4차)는 반대 방향으로 두어 연패가 실제로 결정했음을 못박는다.
     */
    @Test
    fun `losses 가 같으면 연패가 많은 쪽이 앞선다`() {
        val longStreak = stat(userId = 9L, losses = 5, totalChallenges = 20, currentLossStreak = 3)
        val shortStreak = stat(userId = 1L, losses = 5, totalChallenges = 5, currentLossStreak = 1)

        val ranked = listOf(shortStreak, longStreak).rankedAsLosers(me = 9L)

        assertEquals(listOf(9L, 1L), ranked.map { it.userId }, "연패가 losses 다음 축이다")
    }

    /**
     * 3차 키는 `lossRate` DESC — **`losses` 와 연패가 같을 때만** 갈린다.
     * userId(4차)를 반대 방향으로 두어 패율이 결정했음을 확인한다.
     */
    @Test
    fun `losses 와 연패가 같으면 패배율이 높은 쪽이 앞선다`() {
        val highRate = stat(userId = 9L, losses = 5, totalChallenges = 10, currentLossStreak = 2)
        val lowRate = stat(userId = 1L, losses = 5, totalChallenges = 20, currentLossStreak = 2)

        val ranked = listOf(lowRate, highRate).rankedAsLosers(me = 9L)

        assertEquals(listOf(9L, 1L), ranked.map { it.userId })
        assertEquals(listOf(50, 25), ranked.map { it.lossRate })
    }

    /**
     * 4차 키는 `userId` ASC. 앞 3키가 전부 같은 경우가 실제로 흔하다 — **챌린지를 아직 안 한
     * 친구들은 전부 (0, 0, 0)** 이다 ([rankedAsLosers] KDoc).
     */
    @Test
    fun `앞 3키가 모두 같으면 userId 오름차순이다`() {
        val ranked = listOf(stat(30L), stat(10L), stat(20L)).rankedAsLosers(me = 10L)

        assertEquals(listOf(10L, 20L, 30L), ranked.map { it.userId })
    }

    /**
     * 🔴 **입력 순서 무관 = 결정성.** 같은 원소 집합을 서로 다른 순서로 넣어 결과가 완전히
     * 동일한지 본다. `userId ASC` 타이브레이커가 없으면 앞 3키가 같은 원소끼리
     * `sortedWith` 의 안정 정렬 특성상 **입력 순서가 그대로 남아** 여기서 갈린다 — 실제 운영에서는
     * PostgreSQL 이 매 호출마다 다른 row 순서를 줄 수 있으므로 **새로고침마다 포디움이 바뀐다.**
     */
    @Test
    fun `입력 순서가 달라도 결과가 완전히 동일하다`() {
        val stats = listOf(
            stat(7L),
            stat(3L),
            stat(11L, losses = 5, totalChallenges = 10, currentLossStreak = 2),
            stat(2L, losses = 5, totalChallenges = 10, currentLossStreak = 2),
            stat(5L, losses = 5, totalChallenges = 20, currentLossStreak = 2),
            stat(8L, losses = 9, totalChallenges = 9, currentLossStreak = 9),
        )

        assertEquals(stats.rankedAsLosers(me = 3L), stats.reversed().rankedAsLosers(me = 3L))
    }

    /**
     * 🔴 **회귀 테스트: `lossRate` 를 1차 키로 되돌리면 실패한다.**
     *
     * 기획 서사("많이 진 놈이 대장")를 코드로 고정한 지점이다. 패율을 1차로 두면 **1전 1패(100%)가
     * 25패(78%)를 제치고 개돼지왕**이 되는데, 그건 기획의 정반대 결과다 ([rankedAsLosers] KDoc).
     * 패율은 동률을 가르는 **세 번째** 축이지 대표 지표가 아니다.
     */
    @Test
    fun `1전 1패가 개돼지왕이 되지 않는다`() {
        val onceLoser = stat(userId = 1L, losses = 1, totalChallenges = 1, currentLossStreak = 1)
        val realLoser = stat(userId = 2L, losses = 25, totalChallenges = 32, currentLossStreak = 1)

        val ranked = listOf(onceLoser, realLoser).rankedAsLosers(me = 1L)

        assertEquals(2L, ranked.first { it.rank == 1 }.userId, "25패가 1패를 이겨야 한다")
        assertEquals(100, ranked.first { it.userId == 1L }.lossRate, "패율은 여전히 100 이지만 rank 를 결정하지 않는다")
        assertEquals(78, ranked.first { it.userId == 2L }.lossRate)
    }

    // ─── rankedAsLosers — rank / isMe ───────────────────────────

    @Test
    fun `rank 는 1부터 연속 고유값이다`() {
        val stats = listOf(
            stat(1L, losses = 3, totalChallenges = 3),
            stat(2L, losses = 9, totalChallenges = 20),
            stat(3L),
            stat(4L, losses = 1, totalChallenges = 10),
            stat(5L, losses = 7, totalChallenges = 7),
        )

        val ranks = stats.rankedAsLosers(me = 1L).map { it.rank }

        assertEquals((1..5).toList(), ranks)
    }

    /**
     * 🔴 **공동 순위가 생기지 않는다.** 지표가 전부 같은 3명을 넣어도 1/2/3 이 하나씩 나온다.
     * Top3 포디움이 자리를 하나씩 그리는 구조라 공동 1위가 오면 레이아웃이 깨지는데, 그 방어를
     * 앱에 시키지 않고 서버가 원천 차단한다 ([rankedAsLosers] KDoc).
     */
    @Test
    fun `지표가 전부 같아도 공동 순위가 생기지 않는다`() {
        val same = listOf(
            stat(1L, losses = 4, totalChallenges = 8, currentLossStreak = 2),
            stat(2L, losses = 4, totalChallenges = 8, currentLossStreak = 2),
            stat(3L, losses = 4, totalChallenges = 8, currentLossStreak = 2),
        )

        val ranked = same.rankedAsLosers(me = 1L)

        assertEquals(listOf(1, 2, 3), ranked.map { it.rank })
        assertEquals(3, ranked.map { it.rank }.toSet().size, "rank 에 중복이 없어야 한다")
    }

    @Test
    fun `isMe 는 정확히 1건만 true 이고 그게 me 다`() {
        val stats = listOf(stat(10L, losses = 3), stat(20L, losses = 5), stat(30L, losses = 1))

        val ranked = stats.rankedAsLosers(me = 20L)

        val mine = ranked.filter { it.isMe }
        assertEquals(1, mine.size)
        assertEquals(20L, mine.single().userId)
    }

    /**
     * ⚠️ **정상 경로가 아니다.** repository 가 항상 나를 포함시키므로(`findMeAndFriendStats` —
     * `WHERE u.id = :me OR ...`) 실제로는 발생하지 않는다. 함수 자체는 이 상황을 방어하지 않는다는
     * **현재 동작을 기록**하는 테스트다 — 불변식("isMe 는 정확히 1건")은 repository 가 지킨다.
     */
    @Test
    fun `me 가 목록에 없으면 isMe 가 전부 false 다`() {
        val ranked = listOf(stat(10L, losses = 3), stat(20L, losses = 5)).rankedAsLosers(me = 99L)

        assertEquals(2, ranked.size)
        assertTrue(ranked.none { it.isMe }, "함수는 방어하지 않는다 — 불변식은 repository 가 지킨다")
    }

    /** 빈 입력이 예외가 아니라 빈 출력이다. (실제로는 최소 1건이 오지만 함수는 터지지 않는다.) */
    @Test
    fun `빈 입력은 빈 출력이다`() {
        assertEquals(emptyList<LoserRankingEntry>(), emptyList<LoserStat>().rankedAsLosers(me = 1L))
    }

    /**
     * 🔴 **정렬에 쓴 값과 응답에 나가는 값이 같은 `lossRate` 여야 한다.** 반올림 전 실수로 정렬하고
     * 반올림 후 정수를 응답하면, 같은 퍼센트로 표시되는 두 사람의 순서가 화면에서 설명되지 않는다
     * ([rankedAsLosers] 본문 주석). `losses`·연패를 같게 두어 패율만 순서를 결정하게 하고,
     * rank 순서와 `lossRate` 값이 서로 모순되지 않는지 본다.
     */
    @Test
    fun `정렬에 쓴 lossRate 와 응답에 나가는 lossRate 가 같은 값이다`() {
        val stats = listOf(
            stat(1L, losses = 5, totalChallenges = 40, currentLossStreak = 1), // 13
            stat(2L, losses = 5, totalChallenges = 8, currentLossStreak = 1), // 63
            stat(3L, losses = 5, totalChallenges = 10, currentLossStreak = 1), // 50
        )

        val ranked = stats.rankedAsLosers(me = 1L)

        assertEquals(listOf(2L, 3L, 1L), ranked.map { it.userId })
        assertEquals(listOf(63, 50, 13), ranked.map { it.lossRate })
        // 응답 값이 곧 정렬 키다 — rank 가 오를수록 lossRate 가 내려간다(여기서는 앞 2키가 동일하므로).
        assertEquals(ranked.map { it.lossRate }.sortedDescending(), ranked.map { it.lossRate })
        // 그리고 그 값은 lossRateOf 가 계산한 값 그대로다.
        ranked.forEach { entry ->
            val source = stats.single { it.userId == entry.userId }
            assertEquals(lossRateOf(source.losses, source.totalChallenges), entry.lossRate)
        }
    }

    // ─── rankedAsLosers — totalChallenges ───────────────────────

    /**
     * `totalChallenges` 는 계산 대상이 아니라 **원본 사실 그대로 실려 나간다.** 서버가 여기서
     * 가공(0 보정, 상한 등)을 하기 시작하면 앱의 `== 0` 판정이 규칙에 의존하게 된다
     * ([LoserRankingEntry] KDoc — 불리언이 아니라 Int 인 이유).
     */
    @Test
    fun `totalChallenges 가 원본 그대로 실린다`() {
        val stats = listOf(
            stat(1L, losses = 25, totalChallenges = 32, currentLossStreak = 7),
            stat(2L, losses = 5, totalChallenges = 10, currentLossStreak = 1),
            stat(3L, losses = 0, totalChallenges = 0, currentLossStreak = 0),
        )

        val ranked = stats.rankedAsLosers(me = 1L)

        ranked.forEach { entry ->
            val source = stats.single { it.userId == entry.userId }
            assertEquals(source.totalChallenges, entry.totalChallenges, "userId=${entry.userId}")
        }
    }

    /**
     * 🔴 **이 필드를 추가한 이유 자체를 고정하는 테스트다.**
     *
     * 챌린지를 한 번도 안 한 사람(0전 0패)과 전승한 사람(12전 0패)은 `losses` 도 `lossRate` 도
     * 둘 다 0 이라 **응답 행이 글자 하나까지 같아진다.** 정반대 상태인데 화면이 구분할 수단이
     * 없다. `totalChallenges` 만이 둘을 가르고, 앱은 그 값이 `== 0` 일 때 캡션을 "아직 기록 없음"
     * 으로 대체한다 ([LoserRankingEntry] KDoc).
     *
     * 이 필드가 응답에서 빠지면 여기서 컴파일이 막히거나 단언이 깨진다.
     */
    @Test
    fun `0전 0패와 전승 0패는 totalChallenges 로만 구분된다`() {
        val newbie = stat(userId = 1L, losses = 0, totalChallenges = 0, currentLossStreak = 0)
        val undefeated = stat(userId = 2L, losses = 0, totalChallenges = 12, currentLossStreak = 0)

        val ranked = listOf(newbie, undefeated).rankedAsLosers(me = 1L)

        val newbieEntry = ranked.single { it.userId == 1L }
        val undefeatedEntry = ranked.single { it.userId == 2L }

        // 기존 지표로는 두 사람이 완전히 같다 — 그래서 화면이 구분하지 못한다.
        assertEquals(0, newbieEntry.losses)
        assertEquals(0, undefeatedEntry.losses)
        assertEquals(newbieEntry.losses, undefeatedEntry.losses)
        assertEquals(0, newbieEntry.lossRate)
        assertEquals(0, undefeatedEntry.lossRate)
        assertEquals(newbieEntry.lossRate, undefeatedEntry.lossRate)

        // 유일하게 갈리는 값.
        assertEquals(0, newbieEntry.totalChallenges, "챌린지 미경험 = 0")
        assertEquals(12, undefeatedEntry.totalChallenges, "전승 = 12")
        assertNotEquals(newbieEntry.totalChallenges, undefeatedEntry.totalChallenges)
    }
}
