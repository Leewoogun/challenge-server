package com.lwg.challenge.core.challenge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * KST 자정 환산 경계 테스트 (challenge-create T-B4).
 *
 * 이 로직이 틀리면 "오늘 자정" 챌린지가 이미 지난 시각으로 생성되거나(하루 전 자정),
 * 하루 늦게 끝나거나, UTC 날짜가 바뀌는 15:00Z 근처에서 challenge_date 가 하루 어긋난다.
 * 전부 사용자 눈에 바로 보이는 버그라 경계값을 명시적으로 못박는다.
 *
 * 표기 규약: `...Z` 는 UTC 절대시각, "KST hh:mm" 은 그 시각의 한국 현지 표시.
 */
class KstDeadlineCalculatorTest {

    // ─── calculate: TODAY ────────────────────────────────────────

    @Test
    fun `TODAY - 낮에 생성하면 오늘 날짜 + 익일 KST 자정 마감 (계약서 예시)`() {
        // api-contract 의 예시 그대로: KST 2026-07-28 14:00
        val now = Instant.parse("2026-07-28T05:00:00Z")

        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TODAY, now)

        assertEquals(LocalDate.of(2026, 7, 28), schedule.challengeDate)
        // KST 07-29 00:00 == 07-28T15:00Z
        assertEquals(LocalDateTime.of(2026, 7, 28, 15, 0), schedule.deadline)
    }

    @Test
    fun `TODAY - 마감은 오늘의 시작이 아니라 끝이다`() {
        val now = Instant.parse("2026-07-28T05:00:00Z")

        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TODAY, now)

        // 회귀 방지: challengeDate.atStartOfDay() 로 구현하면 07-27T15:00Z (이미 지난 시각) 가 나온다.
        assertTrue(
            schedule.deadline.toInstant(java.time.ZoneOffset.UTC) > now,
            "TODAY 마감은 반드시 생성 시점보다 미래여야 한다",
        )
    }

    @Test
    fun `TODAY - UTC 날짜 경계(15시Z) 직후는 KST 로 이미 다음날이다`() {
        // 15:00:00Z == KST 07-29 00:00 (자정 정각) → KST 기준 날짜는 07-29
        val now = Instant.parse("2026-07-28T15:00:00Z")

        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TODAY, now)

        assertEquals(LocalDate.of(2026, 7, 29), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2026, 7, 29, 15, 0), schedule.deadline)
    }

    @Test
    fun `TODAY - UTC 날짜 경계(15시Z) 직전은 아직 KST 로 같은날이다`() {
        // 14:59:59Z == KST 07-28 23:59:59 → 남은 시간 1초짜리 챌린지
        val now = Instant.parse("2026-07-28T14:59:59Z")

        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TODAY, now)

        assertEquals(LocalDate.of(2026, 7, 28), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2026, 7, 28, 15, 0), schedule.deadline)
    }

    @Test
    fun `TODAY - UTC 자정 직후는 KST 로 같은날 오전 9시다`() {
        // 00:00Z == KST 07-28 09:00. UTC 날짜와 KST 날짜가 같은 구간.
        val now = Instant.parse("2026-07-28T00:00:00Z")

        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TODAY, now)

        assertEquals(LocalDate.of(2026, 7, 28), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2026, 7, 28, 15, 0), schedule.deadline)
    }

    @Test
    fun `TODAY - UTC 자정 직전은 KST 로 이미 다음날 오전이다`() {
        // 23:59Z == KST 07-29 08:59 → KST 날짜는 이미 07-29
        val now = Instant.parse("2026-07-28T23:59:00Z")

        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TODAY, now)

        assertEquals(LocalDate.of(2026, 7, 29), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2026, 7, 29, 15, 0), schedule.deadline)
    }

    // ─── calculate: TOMORROW ─────────────────────────────────────

    @Test
    fun `TOMORROW - 낮에 생성하면 내일 날짜 + 익익일 KST 자정 마감`() {
        val now = Instant.parse("2026-07-28T05:00:00Z") // KST 07-28 14:00

        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TOMORROW, now)

        assertEquals(LocalDate.of(2026, 7, 29), schedule.challengeDate)
        // KST 07-30 00:00 == 07-29T15:00Z
        assertEquals(LocalDateTime.of(2026, 7, 29, 15, 0), schedule.deadline)
    }

    @Test
    fun `TOMORROW - 심야 생성 구제 시나리오`() {
        // spec 결정 3 의 동기: KST 07-28 23:50 에 TODAY 를 고르면 10분짜리 챌린지가 된다.
        // 같은 시각 TOMORROW 를 고르면 07-29 하루가 통째로 주어진다.
        val now = Instant.parse("2026-07-28T14:50:00Z")

        val today = KstDeadlineCalculator.calculate(DeadlineType.TODAY, now)
        val tomorrow = KstDeadlineCalculator.calculate(DeadlineType.TOMORROW, now)

        assertEquals(LocalDate.of(2026, 7, 28), today.challengeDate)
        assertEquals(LocalDate.of(2026, 7, 29), tomorrow.challengeDate)
        assertEquals(
            LocalDateTime.of(2026, 7, 29, 15, 0),
            tomorrow.deadline,
        )
        // 두 선택지의 challenge_date 가 다르므로 같은 상대에게 둘 다 거는 것이 중복이 아니다.
        assertTrue(today.challengeDate < tomorrow.challengeDate)
    }

    @Test
    fun `TOMORROW - 월말이면 다음달로 넘어간다`() {
        val now = Instant.parse("2026-07-31T05:00:00Z") // KST 07-31 14:00

        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TOMORROW, now)

        assertEquals(LocalDate.of(2026, 8, 1), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2026, 8, 1, 15, 0), schedule.deadline)
    }

    @Test
    fun `TOMORROW - 연말이면 해가 바뀐다`() {
        val now = Instant.parse("2026-12-31T05:00:00Z") // KST 12-31 14:00

        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TOMORROW, now)

        assertEquals(LocalDate.of(2027, 1, 1), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2027, 1, 1, 15, 0), schedule.deadline)
    }

    @Test
    fun `윤년 2월 29일도 정상 처리된다`() {
        val now = Instant.parse("2028-02-28T05:00:00Z") // KST 02-28 14:00 (2028 은 윤년)

        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TOMORROW, now)

        assertEquals(LocalDate.of(2028, 2, 29), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2028, 2, 29, 15, 0), schedule.deadline)
    }

    // ─── endOfKstDayInUtc ────────────────────────────────────────

    @Test
    fun `endOfKstDayInUtc - KST 는 서머타임이 없어 항상 같은날 09시 UTC 다`() {
        // 여름(7월) / 겨울(1월) 모두 UTC+9 고정. DST 가 있는 타임존으로 바꾸면 이 테스트가 깨진다.
        assertEquals(
            LocalDateTime.of(2026, 7, 28, 15, 0),
            KstDeadlineCalculator.endOfKstDayInUtc(LocalDate.of(2026, 7, 28)),
        )
        assertEquals(
            LocalDateTime.of(2026, 1, 15, 15, 0),
            KstDeadlineCalculator.endOfKstDayInUtc(LocalDate.of(2026, 1, 15)),
        )
    }

    // ─── isExpired ───────────────────────────────────────────────

    @Test
    fun `isExpired - 마감 전이면 false`() {
        val deadline = LocalDateTime.of(2026, 7, 28, 15, 0)

        assertFalse(KstDeadlineCalculator.isExpired(deadline, Instant.parse("2026-07-28T14:59:59Z")))
    }

    @Test
    fun `isExpired - 마감 정각은 아직 유효하다`() {
        val deadline = LocalDateTime.of(2026, 7, 28, 15, 0)

        // 경계에서는 사용자에게 유리한 쪽. 정각 수락은 통과시킨다.
        assertFalse(KstDeadlineCalculator.isExpired(deadline, Instant.parse("2026-07-28T15:00:00Z")))
    }

    @Test
    fun `isExpired - 마감 1초 후면 true`() {
        val deadline = LocalDateTime.of(2026, 7, 28, 15, 0)

        assertTrue(KstDeadlineCalculator.isExpired(deadline, Instant.parse("2026-07-28T15:00:01Z")))
    }

    @Test
    fun `isExpired - deadline 은 UTC 로 해석된다`() {
        // deadline 을 KST 로 잘못 해석하면 9시간 어긋나 만료 판정이 뒤집힌다.
        val deadline = LocalDateTime.of(2026, 7, 28, 15, 0)

        // 07-28T20:00Z 는 deadline(15:00Z) 을 5시간 지났다. KST 해석이면 아직 안 지난 것으로 오판.
        assertTrue(KstDeadlineCalculator.isExpired(deadline, Instant.parse("2026-07-28T20:00:00Z")))
    }

    // ─── calculate 결과와 isExpired 의 정합성 ─────────────────────

    @Test
    fun `방금 생성한 챌린지는 어느 유형이든 만료 상태가 아니다`() {
        // 자정 직전 1초를 포함한 여러 시각에서 생성 직후 만료로 판정되면 안 된다.
        val instants = listOf(
            "2026-07-28T00:00:00Z",
            "2026-07-28T05:00:00Z",
            "2026-07-28T14:59:59Z",
            "2026-07-28T15:00:00Z",
            "2026-07-28T23:59:59Z",
        ).map(Instant::parse)

        for (now in instants) {
            for (type in DeadlineType.entries) {
                val schedule = KstDeadlineCalculator.calculate(type, now)
                assertFalse(
                    KstDeadlineCalculator.isExpired(schedule.deadline, now),
                    "생성 직후 만료로 판정됨: type=$type now=$now deadline=${schedule.deadline}",
                )
            }
        }
    }
}
