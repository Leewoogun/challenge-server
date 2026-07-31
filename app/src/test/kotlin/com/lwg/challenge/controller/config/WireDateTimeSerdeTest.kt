package com.lwg.challenge.controller.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lwg.challenge.controller.common.WIRE_DATE
import com.lwg.challenge.controller.common.WIRE_DATETIME
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 전역 시간 직렬화·**역직렬화** 규약 테스트 (datetime-model-migration T-B4, api-contract §5).
 *
 * ## 왜 "합성 DTO" 인가
 *
 * spec 수용 기준에 "서버가 요청 본문의 같은 패턴 문자열을 역직렬화한다" 가 있는데,
 * **현재 요청 DTO 에 시간 필드가 하나도 없다** (`CreateChallengeBody` / `AcceptChallengeBody` /
 * `SendFriendRequestBody`). 그대로 두면 이 기준은 검증 대상이 없어 **공허하게 통과**한다.
 *
 * 그래서 시간 필드를 가진 DTO 를 **테스트 안에서 만들어** 규약을 지금 고정한다.
 * 미래에 시간 필드를 가진 요청이 실제로 생겨도 전역 설정이 그대로 받아준다.
 *
 * ## ⚠️ 이 테스트가 증명하지 못하는 것
 *
 * - ✅ 증명함: **전역 설정이 `yyyy-MM-dd HH:mm:ss` 패턴을 읽고 쓴다**
 * - ❌ 증명 못 함: **실제 엔드포인트가 그 형식을 받는다**
 *
 * 시간 필드를 가진 요청 DTO 가 실제로 생기는 시점에 **그 엔드포인트의 슬라이스 테스트가 따로
 * 필요하다.** "이미 검증됨" 으로 오해하지 말 것 (api-contract §5 에 같은 문구로 명시돼 있다).
 *
 * 응답 쪽 실제 엔드포인트 검증은 `ChallengeCommandControllerTest` / `FriendControllerTest` 가
 * 실제 JSON 문자열을 assert 하는 방식으로 이미 커버한다.
 */
class WireDateTimeSerdeTest {

    /** 시간 필드를 가진 합성 요청 DTO. 프로덕션에는 아직 이런 DTO 가 없다. */
    data class SyntheticTimeBody(
        val startAt: LocalDateTime,
        val targetDate: LocalDate,
    )

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JacksonDateTimeConfig().wireDateTimeModule())

    // ─── 역직렬화 (요청) ─────────────────────────────────────────

    @Test
    fun `요청 본문의 yyyy-MM-dd HH-mm-ss 문자열을 읽는다`() {
        val json = """{"startAt":"2026-07-28 15:04:05","targetDate":"2026-07-28"}"""

        val body = mapper.readValue(json, SyntheticTimeBody::class.java)

        assertEquals(LocalDateTime.of(2026, 7, 28, 15, 4, 5), body.startAt)
        assertEquals(LocalDate.of(2026, 7, 28), body.targetDate)
    }

    @Test
    fun `ISO T 구분자는 거부한다 - 관대하게 받지 않는다`() {
        // 모바일도 T 구분자를 거부하는 것을 테스트로 고정해 뒀다(mobile-dev 실측).
        // 양쪽이 같은 엄격함을 가져야 포맷이 실제로 구분된다.
        val json = """{"startAt":"2026-07-28T15:04:05","targetDate":"2026-07-28"}"""

        assertThrows<Exception> { mapper.readValue(json, SyntheticTimeBody::class.java) }
    }

    @Test
    fun `Z suffix 가 붙으면 거부한다 - 마이그레이션 전 포맷이 흘러들면 잡힌다`() {
        val json = """{"startAt":"2026-07-28T15:04:05Z","targetDate":"2026-07-28"}"""

        assertThrows<Exception> { mapper.readValue(json, SyntheticTimeBody::class.java) }
    }

    // ─── 직렬화 (응답) ───────────────────────────────────────────

    @Test
    fun `응답으로 나갈 때 공백 구분자 패턴을 쓴다`() {
        val body = SyntheticTimeBody(
            startAt = LocalDateTime.of(2026, 7, 28, 15, 4, 5),
            targetDate = LocalDate.of(2026, 7, 28),
        )

        val json = mapper.writeValueAsString(body)

        assertTrue(json.contains(""""startAt":"2026-07-28 15:04:05""""), "actual=$json")
        assertTrue(json.contains(""""targetDate":"2026-07-28""""), "actual=$json")
        // 금지 표기가 섞이지 않았는지 — 계약이 T·Z·offset·밀리초를 전부 금지한다.
        assertTrue(!json.contains("T15:"), "T 구분자가 새어나왔다: $json")
        assertTrue(!json.contains("Z\""), "Z suffix 가 새어나왔다: $json")
        assertTrue(!json.contains("."), "밀리초/나노초가 새어나왔다: $json")
    }

    @Test
    fun `나노초가 있어도 초까지만 직렬화된다`() {
        val body = SyntheticTimeBody(
            startAt = LocalDateTime.of(2026, 7, 28, 15, 4, 5, 123_456_789),
            targetDate = LocalDate.of(2026, 7, 28),
        )

        val json = mapper.writeValueAsString(body)

        assertTrue(json.contains(""""startAt":"2026-07-28 15:04:05""""), "actual=$json")
    }

    @Test
    fun `직렬화 - 역직렬화 왕복이 값을 보존한다`() {
        val original = SyntheticTimeBody(
            startAt = LocalDateTime.of(2026, 12, 31, 23, 59, 59),
            targetDate = LocalDate.of(2027, 1, 1),
        )

        val roundTripped = mapper.readValue(mapper.writeValueAsString(original), SyntheticTimeBody::class.java)

        assertEquals(original, roundTripped)
    }

    // ─── 패턴 상수 자체 ──────────────────────────────────────────

    @Test
    fun `패턴 상수가 계약과 일치한다`() {
        // 상수가 바뀌면 계약 위반이므로 여기서 고정한다.
        assertEquals("yyyy-MM-dd HH:mm:ss", WIRE_DATETIME)
        assertEquals("yyyy-MM-dd", WIRE_DATE)
    }
}
