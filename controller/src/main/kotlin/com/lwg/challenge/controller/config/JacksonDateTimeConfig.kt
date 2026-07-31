package com.lwg.challenge.controller.config

import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer
import com.lwg.challenge.controller.common.WIRE_DATE
import com.lwg.challenge.controller.common.WIRE_DATETIME
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `LocalDateTime` / `LocalDate` 의 전역 직렬화·역직렬화 포맷 (ADR-0010).
 *
 * ## 이건 **안전망**이고 정본은 DTO 의 `@JsonFormat` 이다
 *
 * api-contract §1.3 이 확정한 방침: **필드마다 `@JsonFormat` 을 명시하는 것이 정본**,
 * 전역 설정은 어노테이션을 빠뜨렸을 때를 위한 안전망. challenge-create 에서 `LocalDate` 가
 * `[2026,7,28]` 배열로 나가는 회귀를 막으려고 세운 원칙을 그대로 승계한다.
 *
 * 둘 다 두는 이유는 `ClockConfig`/`KstTime` 과 같은 사고방식이다 — 명시적 경로가 정본이고,
 * 놓쳤을 때 결과가 **틀리는 대신 맞도록** 기본값을 깔아 둔다.
 *
 * ## 역직렬화도 함께 등록하는 이유
 *
 * 현재 요청 DTO 에는 시간 필드가 **하나도 없다**(`CreateChallengeBody` / `AcceptChallengeBody` /
 * `SendFriendRequestBody`). 그래서 spec 의 "요청 본문 역직렬화" 수용 기준은 그대로 두면
 * 검증 대상이 없어 **공허하게 통과**한다 (api-contract §5).
 *
 * 여기서 역직렬화를 미리 등록해 두면 미래에 시간 필드를 가진 요청이 생겨도 그대로 동작하고,
 * 합성 DTO 단위 테스트로 그 사실을 **지금** 검증할 수 있다.
 *
 * ⚠️ 다만 이 테스트가 증명하는 건 "전역 설정이 이 패턴을 읽는다" 까지다.
 * "실제 엔드포인트가 그 형식을 받는다" 는 **증명하지 못한다** — 시간 필드를 가진 요청 DTO 가
 * 실제로 생기는 시점에 그 엔드포인트의 슬라이스 테스트가 따로 필요하다 (api-contract §5 명시).
 */
@Configuration
class JacksonDateTimeConfig {

    @Bean
    fun wireDateTimeModule(): SimpleModule {
        val dateTime = DateTimeFormatter.ofPattern(WIRE_DATETIME)
        val date = DateTimeFormatter.ofPattern(WIRE_DATE)

        return SimpleModule("WireDateTimeModule").apply {
            addSerializer(LocalDateTime::class.java, LocalDateTimeSerializer(dateTime))
            addDeserializer(LocalDateTime::class.java, LocalDateTimeDeserializer(dateTime))
            addSerializer(LocalDate::class.java, LocalDateSerializer(date))
            addDeserializer(LocalDate::class.java, LocalDateDeserializer(date))
        }
    }
}
