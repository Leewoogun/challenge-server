package com.lwg.challenge.core.challenge

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 챌린지 마감 유형. 클라이언트가 timestamp 를 직접 보내지 않고 이 두 값 중 하나만 보낸다.
 *
 * 기기 시계 조작 / 타임존 불일치를 차단하고 마감 기준을 서버로 일원화하기 위한 규약
 * (challenge-create api-contract §공통 enum). KST 고정 — 해외 사용자 대응은 범위 밖.
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
 * @property challengeDate KST 기준 날짜. `challenges.challenge_date` (DATE) 에 그대로 저장.
 * @property deadline **KST 기준** 시각. `challenges.deadline` (TIMESTAMP, offset 미저장) 에 그대로 저장.
 */
data class ChallengeSchedule(
    val challengeDate: LocalDate,
    val deadline: LocalDateTime,
)

/**
 * 챌린지 마감 시각 환산기.
 *
 * ## ADR-0010 이후 대폭 단순해졌다
 *
 * 예전에는 `Instant`(UTC) 를 받아 KST 로 날짜를 뽑고 다시 UTC 로 되돌리는 왕복 환산을 했다.
 * 이제 **입출력이 전부 KST `LocalDateTime`** 이라 타임존 변환이 아예 없다.
 * 남은 책임은 하나뿐이다 — **"오늘 자정" 은 오늘의 *끝*(= 익일 00:00)이지 시작이 아니다.**
 *
 * `challengeDate.atStartOfDay()` 를 그대로 쓰면 하루 전 자정(= 오늘의 시작)이 되어 이미 지난
 * 시각이 나온다. 그래서 항상 `challengeDate + 1일` 의 00:00 을 마감으로 삼는다.
 *
 * 모든 함수가 `now` 를 명시적으로 받는다 — 숨은 `now()` 호출은 자정 경계 버그를 재현 불가능하게 만든다.
 */
object KstDeadlineCalculator {

    /**
     * [type] 과 현재 시각(KST)으로 `challengeDate` / `deadline` 을 환산한다.
     *
     * 예) `now` = 2026-07-31 14:00 (KST)
     * - `TODAY`    → challengeDate=2026-07-31, deadline=2026-08-01 00:00
     * - `TOMORROW` → challengeDate=2026-08-01, deadline=2026-08-02 00:00
     *
     * @param now 기준 시각. **KST 벽시계** (`LocalDateTime.now(clock)` 또는 `KstTime.now()`).
     */
    fun calculate(type: DeadlineType, now: LocalDateTime): ChallengeSchedule {
        val today: LocalDate = now.toLocalDate()
        val challengeDate: LocalDate = when (type) {
            DeadlineType.TODAY -> today
            DeadlineType.TOMORROW -> today.plusDays(1)
        }
        return ChallengeSchedule(
            challengeDate = challengeDate,
            deadline = endOfDay(challengeDate),
        )
    }

    /**
     * [date] 가 끝나는 순간 = 익일 00:00.
     *
     * "자정" 을 하루의 끝으로 해석한다. 이 함수가 이 클래스의 존재 이유다.
     */
    fun endOfDay(date: LocalDate): LocalDateTime = date.plusDays(1).atStartOfDay()

    /**
     * 마감이 지났는지 판정한다.
     *
     * `EXPIRED` 상태로 전이시키는 배치가 아직 없어 조회/수락 시점에 lazy 하게 판정한다
     * (challenge-create spec 리스크 항목). DB 의 `status` 는 `PENDING` 인 채로 남는다.
     *
     * 마감 **정각은 아직 유효**하다 (`deadline == now` 이면 false) — 경계에서 사용자에게 유리한 쪽.
     *
     * 두 인자 모두 KST 여야 한다. 한쪽만 UTC 면 9시간 어긋나는데 타입으로는 구분되지 않으므로,
     * 호출부가 `Clock`/`KstTime` 을 통해서만 시각을 얻는 것이 유일한 방어선이다.
     */
    fun isExpired(deadline: LocalDateTime, now: LocalDateTime): Boolean = deadline < now
}
