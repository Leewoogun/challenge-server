package com.lwg.challenge.core.challenge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.TimeZone

/**
 * KST 자정 경계 테스트 (datetime-model-migration T-B4).
 *
 * ## ADR-0010 이후 달라진 점
 *
 * 예전 버전은 `Instant`(UTC) 를 받아 KST 로 날짜를 뽑고 다시 UTC 로 되돌렸기 때문에,
 * 테스트의 절반이 **`15:00Z` 전후의 UTC↔KST 날짜 경계**를 확인하는 데 쓰였다.
 * 이제 입출력이 전부 KST `LocalDateTime` 이라 그 변환 자체가 없어졌고, 해당 케이스도 사라졌다.
 *
 * **다만 그 테스트들이 지키던 의도는 남는다** — "날짜가 하루 어긋나면 안 된다".
 * 그래서 월말·연말·윤년 경계와 "자정은 하루의 끝" 회귀 방지는 그대로 유지하고,
 * 변환이 사라졌다는 사실 자체를 **타임존 무관성 테스트**로 새로 못박는다(마지막 섹션).
 */
class KstDeadlineCalculatorTest {

    // KST 2026-07-31 14:00. 이하 모든 시각은 KST 벽시계다.
    private val afternoon = LocalDateTime.of(2026, 7, 31, 14, 0)

    // ─── calculate: TODAY ────────────────────────────────────────

    @Test
    fun `TODAY - 오늘 날짜 + 익일 자정 마감`() {
        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TODAY, afternoon)

        assertEquals(LocalDate.of(2026, 7, 31), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2026, 8, 1, 0, 0), schedule.deadline)
    }

    @Test
    fun `TODAY - 마감은 오늘의 시작이 아니라 끝이다`() {
        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TODAY, afternoon)

        // 회귀 방지: challengeDate.atStartOfDay() 로 구현하면 07-31 00:00 (이미 지난 시각) 이 나온다.
        assertTrue(
            schedule.deadline > afternoon,
            "TODAY 마감은 반드시 생성 시점보다 미래여야 한다. actual=${schedule.deadline}",
        )
    }

    @Test
    fun `TODAY - 자정 1초 전에 생성해도 오늘이다`() {
        val now = LocalDateTime.of(2026, 7, 31, 23, 59, 59)

        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TODAY, now)

        assertEquals(LocalDate.of(2026, 7, 31), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2026, 8, 1, 0, 0), schedule.deadline)
    }

    @Test
    fun `TODAY - 자정 정각에 생성하면 그날 하루가 통째로 주어진다`() {
        val now = LocalDateTime.of(2026, 7, 31, 0, 0, 0)

        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TODAY, now)

        assertEquals(LocalDate.of(2026, 7, 31), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2026, 8, 1, 0, 0), schedule.deadline)
    }

    // ─── calculate: TOMORROW ─────────────────────────────────────

    @Test
    fun `TOMORROW - 내일 날짜 + 익익일 자정 마감`() {
        val schedule = KstDeadlineCalculator.calculate(DeadlineType.TOMORROW, afternoon)

        assertEquals(LocalDate.of(2026, 8, 1), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2026, 8, 2, 0, 0), schedule.deadline)
    }

    @Test
    fun `TOMORROW - 심야 생성 구제 시나리오`() {
        // spec 결정 3 의 동기: 23:50 에 TODAY 를 고르면 10분짜리 챌린지가 된다.
        val lateNight = LocalDateTime.of(2026, 7, 31, 23, 50)

        val today = KstDeadlineCalculator.calculate(DeadlineType.TODAY, lateNight)
        val tomorrow = KstDeadlineCalculator.calculate(DeadlineType.TOMORROW, lateNight)

        assertEquals(LocalDate.of(2026, 7, 31), today.challengeDate)
        assertEquals(LocalDate.of(2026, 8, 1), tomorrow.challengeDate)
        assertEquals(LocalDateTime.of(2026, 8, 2, 0, 0), tomorrow.deadline)
        // 두 선택지의 challenge_date 가 다르므로 같은 상대에게 둘 다 거는 것이 중복이 아니다.
        assertTrue(today.challengeDate < tomorrow.challengeDate)
    }

    // ─── 날짜 경계 (월말·연말·윤년) ───────────────────────────────

    @Test
    fun `월말이면 다음달로 넘어간다`() {
        val schedule = KstDeadlineCalculator.calculate(
            DeadlineType.TOMORROW,
            LocalDateTime.of(2026, 7, 31, 14, 0),
        )

        assertEquals(LocalDate.of(2026, 8, 1), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2026, 8, 2, 0, 0), schedule.deadline)
    }

    @Test
    fun `연말이면 해가 바뀐다`() {
        val schedule = KstDeadlineCalculator.calculate(
            DeadlineType.TOMORROW,
            LocalDateTime.of(2026, 12, 31, 14, 0),
        )

        assertEquals(LocalDate.of(2027, 1, 1), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2027, 1, 2, 0, 0), schedule.deadline)
    }

    @Test
    fun `윤년 2월 29일도 정상 처리된다`() {
        val schedule = KstDeadlineCalculator.calculate(
            DeadlineType.TOMORROW,
            LocalDateTime.of(2028, 2, 28, 14, 0), // 2028 은 윤년
        )

        assertEquals(LocalDate.of(2028, 2, 29), schedule.challengeDate)
        assertEquals(LocalDateTime.of(2028, 3, 1, 0, 0), schedule.deadline)
    }

    @Test
    fun `TODAY 를 2월 28일에 고르면 마감이 29일 자정이다`() {
        val schedule = KstDeadlineCalculator.calculate(
            DeadlineType.TODAY,
            LocalDateTime.of(2028, 2, 28, 14, 0),
        )

        assertEquals(LocalDateTime.of(2028, 2, 29, 0, 0), schedule.deadline)
    }

    // ─── endOfDay ────────────────────────────────────────────────

    @Test
    fun `endOfDay - 익일 자정을 돌려준다`() {
        assertEquals(
            LocalDateTime.of(2026, 8, 1, 0, 0),
            KstDeadlineCalculator.endOfDay(LocalDate.of(2026, 7, 31)),
        )
        assertEquals(
            LocalDateTime.of(2027, 1, 1, 0, 0),
            KstDeadlineCalculator.endOfDay(LocalDate.of(2026, 12, 31)),
        )
    }

    // ─── isExpired ───────────────────────────────────────────────

    @Test
    fun `isExpired - 마감 전이면 false`() {
        val deadline = LocalDateTime.of(2026, 8, 1, 0, 0)

        assertFalse(KstDeadlineCalculator.isExpired(deadline, LocalDateTime.of(2026, 7, 31, 23, 59, 59)))
    }

    @Test
    fun `isExpired - 마감 정각은 아직 유효하다`() {
        val deadline = LocalDateTime.of(2026, 8, 1, 0, 0)

        // 경계에서는 사용자에게 유리한 쪽. 정각 수락은 통과시킨다.
        assertFalse(KstDeadlineCalculator.isExpired(deadline, LocalDateTime.of(2026, 8, 1, 0, 0)))
    }

    @Test
    fun `isExpired - 마감 1초 후면 true`() {
        val deadline = LocalDateTime.of(2026, 8, 1, 0, 0)

        assertTrue(KstDeadlineCalculator.isExpired(deadline, LocalDateTime.of(2026, 8, 1, 0, 0, 1)))
    }

    @Test
    fun `방금 생성한 챌린지는 어느 유형이든 만료 상태가 아니다`() {
        val moments = listOf(
            LocalDateTime.of(2026, 7, 31, 0, 0, 0),
            LocalDateTime.of(2026, 7, 31, 14, 0, 0),
            LocalDateTime.of(2026, 7, 31, 23, 59, 59),
            LocalDateTime.of(2026, 12, 31, 23, 59, 59),
        )

        for (now in moments) {
            for (type in DeadlineType.entries) {
                val schedule = KstDeadlineCalculator.calculate(type, now)
                assertFalse(
                    KstDeadlineCalculator.isExpired(schedule.deadline, now),
                    "생성 직후 만료로 판정됨: type=$type now=$now deadline=${schedule.deadline}",
                )
            }
        }
    }

    // ─── 🔴 타임존 무관성 (ADR-0010 의 핵심 보장) ─────────────────

    @Test
    fun `JVM 기본 타임존이 UTC 여도 결과가 동일하다`() {
        // pm-lead 조건: "AWS 에서 JVM 이 UTC 로 떠도 시각이 밀리면 안 된다."
        // calculate/isExpired 가 순수 함수(숨은 타임존 참조 없음)임을 실제로 확인한다.
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            val inKst = KstDeadlineCalculator.calculate(DeadlineType.TODAY, afternoon)
            val expiredInKst = KstDeadlineCalculator.isExpired(inKst.deadline, afternoon)

            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val inUtc = KstDeadlineCalculator.calculate(DeadlineType.TODAY, afternoon)
            val expiredInUtc = KstDeadlineCalculator.isExpired(inUtc.deadline, afternoon)

            assertEquals(inKst, inUtc, "JVM 타임존이 바뀌면 안 되는 값이 바뀌었다")
            assertEquals(expiredInKst, expiredInUtc)
            assertEquals(LocalDate.of(2026, 7, 31), inUtc.challengeDate)
            assertEquals(LocalDateTime.of(2026, 8, 1, 0, 0), inUtc.deadline)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
