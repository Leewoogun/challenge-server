package com.lwg.challenge.core.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * [KstTime] 이 **JVM 기본 타임존에 의존하지 않는다**는 것을 확인한다 (ADR-0010).
 *
 * 이 테스트가 존재하는 이유는 구체적이다. 2026-07-31 실측에서 서버 안에 시계가 두 갈래로
 * 갈라져 있었고(`Clock.systemUTC()` vs `LocalDateTime.now()`), 그중 후자는 JVM 기본 타임존을
 * 따랐다. 개발 머신이 `Asia/Seoul` 이라 **우연히** 맞고 있었을 뿐, ADR-0007 의 AWS 배포에서
 * JVM 이 UTC 로 뜨면 9시간이 조용히 어긋난다.
 *
 * pm-lead 조건: "JVM 기본 타임존에 의존하는 방식은 금지 — 코드로 보장되는 경로가 반드시 있어야 한다."
 * 그 보장을 여기서 검증한다.
 *
 * 타임존을 실제로 바꿔가며 확인하므로 `finally` 로 반드시 원복한다 (다른 테스트 오염 방지).
 */
class KstTimeTest {

    @Test
    fun `ZONE 은 Asia Seoul 고정이다`() {
        assertEquals(ZoneId.of("Asia/Seoul"), KstTime.ZONE)
    }

    @Test
    fun `JVM 기본이 UTC 여도 now 는 KST 를 돌려준다`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

            val kst = KstTime.now()
            val jvmDefault = LocalDateTime.now() // JVM 기본 = UTC 인 상태

            // KST 는 UTC+9 이므로 약 9시간 앞서야 한다 (실행 지연 감안해 여유를 둔다).
            val diff = Duration.between(jvmDefault, kst)
            assertTrue(
                diff.toMinutes() in 535..545,
                "JVM 기본이 UTC 일 때 KstTime.now() 는 약 9시간 앞서야 한다. 실제 차이=${diff.toMinutes()}분",
            )
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `JVM 기본이 무엇이든 now 결과가 서로 일치한다`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val fromUtcJvm = KstTime.now()

            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val fromNyJvm = KstTime.now()

            // 두 호출 사이의 실제 경과 시간만큼만 차이나야 한다 (타임존 차이가 섞이면 안 된다).
            val diff = Duration.between(fromUtcJvm, fromNyJvm).abs()
            assertTrue(
                diff.toMinutes() < 1,
                "JVM 타임존이 바뀌었는데 KstTime.now() 결과가 이동했다. 차이=${diff.toMinutes()}분",
            )
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `today 는 now 의 날짜와 일치한다`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

            // KST 자정 직후에 실행되면 now 와 today 사이에 날짜가 바뀔 수 있으므로
            // 두 값을 나란히 찍어 비교한다 (경계에서만 드물게 어긋나며, 그건 정상이다).
            val now = KstTime.now()
            val today = KstTime.today()

            assertTrue(
                today == now.toLocalDate() || today == now.toLocalDate().plusDays(1),
                "today=$today 가 now=$now 의 날짜와 무관하다",
            )
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
