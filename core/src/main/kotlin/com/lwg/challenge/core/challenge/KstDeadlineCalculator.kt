package com.lwg.challenge.core.challenge

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 챌린지 마감 유형. 클라이언트가 timestamp 를 직접 보내지 않고 이 두 값 중 하나만 보낸다.
 *
 * 기기 시계 조작 / 타임존 불일치를 차단하고 마감 기준을 서버로 일원화하기 위한 규약
 * (challenge-create api-contract §공통 enum). KST(UTC+9) 고정 — 해외 사용자 대응은 범위 밖.
 *
 * `:core` 에 두는 이유: `:domain:model` 이 `:core` 를 의존하므로 반대 방향 의존이 불가능하고,
 * [KstDeadlineCalculator] 의 입력 타입이라 같은 모듈에 있는 편이 응집도가 높다.
 */
enum class DeadlineType {
    /** 오늘 자정 마감. `challengeDate` = KST 오늘. */
    TODAY,

    /** 내일 자정 마감. `challengeDate` = KST 내일. */
    TOMORROW,
}

/**
 * [DeadlineType] 을 `challenges` 테이블에 저장할 두 값으로 환산한 결과.
 *
 * @property challengeDate KST 기준 날짜. DB `challenges.challenge_date` (DATE) 에 그대로 저장.
 * @property deadline **UTC 기준** 시각. DB `challenges.deadline` 은 timezone 없는 TIMESTAMP 이고
 *   프로젝트 전체가 이를 UTC 로 간주한다 (ActiveChallengeController 의 `toInstant(ZoneOffset.UTC)` 와 짝).
 */
data class ChallengeSchedule(
    val challengeDate: LocalDate,
    val deadline: LocalDateTime,
)

/**
 * 챌린지 마감 시각 환산기.
 *
 * "오늘 자정" 은 **오늘이 끝나는 순간** = 익일 00:00 KST 를 의미한다. `LocalDate.atTime(0,0)` 을
 * 그대로 쓰면 하루 전 자정(=오늘의 시작)이 되어 이미 지난 시각이 되므로, 항상 `challengeDate + 1일`
 * 의 00:00 을 마감으로 삼는다.
 *
 * 모든 함수가 `now` 를 명시적으로 받는다 — 시간 의존 로직을 테스트에서 고정할 수 있어야 하고,
 * 숨은 `Instant.now()` 는 자정 경계 버그를 재현 불가능하게 만든다.
 */
object KstDeadlineCalculator {

    /** 프로젝트 고정 타임존. 챌린지의 "하루" 는 항상 KST 기준으로 끊는다. */
    val KST: ZoneId = ZoneId.of("Asia/Seoul")

    /**
     * [type] 과 현재 시각으로 `challengeDate` / `deadline` 을 환산한다.
     *
     * 예) `now` = 2026-07-28T05:00:00Z (= KST 07-28 14:00)
     * - `TODAY`    → challengeDate=2026-07-28, deadline=2026-07-28T15:00 UTC (= KST 07-29 00:00)
     * - `TOMORROW` → challengeDate=2026-07-29, deadline=2026-07-29T15:00 UTC (= KST 07-30 00:00)
     *
     * @param now 기준 시각 (UTC 절대시각).
     */
    fun calculate(type: DeadlineType, now: Instant): ChallengeSchedule {
        val today: LocalDate = now.atZone(KST).toLocalDate()
        val challengeDate: LocalDate = when (type) {
            DeadlineType.TODAY -> today
            DeadlineType.TOMORROW -> today.plusDays(1)
        }
        return ChallengeSchedule(
            challengeDate = challengeDate,
            deadline = endOfKstDayInUtc(challengeDate),
        )
    }

    /**
     * [date] (KST 기준 날짜) 가 끝나는 순간을 UTC LocalDateTime 으로 반환.
     *
     * KST 24:00 == 익일 KST 00:00. KST 는 서머타임이 없어 항상 UTC+9 이므로 결과는 `date` 09:00 UTC 다.
     */
    fun endOfKstDayInUtc(date: LocalDate): LocalDateTime =
        date.plusDays(1)
            .atStartOfDay(KST)
            .toInstant()
            .atZone(ZoneOffset.UTC)
            .toLocalDateTime()

    /**
     * 마감이 지났는지 판정한다.
     *
     * `EXPIRED` 상태로의 실제 전이 주체(배치)가 아직 없어, 조회/수락 시점에 lazy 하게 판정한다
     * (challenge-create spec 리스크 항목). DB 의 `status` 는 `PENDING` 인 채로 남는다.
     *
     * 마감 **정각은 아직 유효**하다 (`deadline == now` 이면 false). 경계에서 사용자에게 유리한 쪽.
     *
     * @param deadline UTC 기준 마감 시각 (`challenges.deadline` 그대로).
     * @param now 기준 시각 (UTC 절대시각).
     */
    fun isExpired(deadline: LocalDateTime, now: Instant): Boolean =
        deadline.toInstant(ZoneOffset.UTC) < now
}
