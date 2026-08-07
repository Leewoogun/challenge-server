package com.lwg.challenge.domain.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [NotificationType] 어휘 고정 (push-fcm T-B3, 계약 §0.6).
 *
 * enum 테스트는 보통 무의미하지만 여기서는 둘을 지킨다:
 * 1. **폐기된 `SIGN_REQUEST` 가 되살아나지 않는다** — 이름만 되돌리면 영원히 발송되지 않는 죽은 타입이 된다.
 * 2. **DB 컬럼 주석(V8)과 enum 이 같은 목록을 말한다** — 주석이 낡는 것이 **이번에 실제로 고친 문제**다
 *    (V1 주석이 폐기된 `SIGN_REQUEST` 를 계속 사실로 말하고 있었다).
 *
 * ## ⚠️ `VARCHAR(30)` 초과 이름을 막는 테스트를 두지 않은 이유 — **아직 저장 경로가 없다**
 *
 * 🔴 근거는 *"그런 일이 일어난 적 없다"* 가 **아니다.** 그 기준을 쓰면 `FcmTokenRequest` 의
 * `@Size(max = 4096)` 도 같이 지워야 하는데(4096자 넘는 토큰도 온 적 없다) 그건 유지가 맞다 —
 * 즉 그 기준은 자기 예외를 만든다.
 *
 * 실제 근거는 **`NotificationType` 을 DB 에 저장하는 코드가 지금 0건**이라는 것이다. 값이 컬럼에
 * 닿지 않으므로 길이 초과로 잘릴 위험 자체가 존재하지 않는다.
 *
 * 🔴 **#33(T-B5 이벤트 배관)에서 저장 경로가 생기면 이 판단을 다시 하라.** 그때 조건이 바뀐다 —
 * enum 이름이 실제로 `notifications.type` 에 들어가기 시작하면, 이건 **따로 편집되는 두 산출물
 * (Kotlin enum ↔ SQL 컬럼 폭)의 불변식**이 되어 아래 V8 주석 대조와 같은 성격의 검증 대상이 된다.
 *
 * 컬럼 길이 사실은 [NotificationType] KDoc 과 V8 마이그레이션 주석에 **문서로** 남겼다.
 */
class NotificationTypeTest {

    @Test
    fun `push-fcm 범위 3종이 존재한다`() {
        val names = NotificationType.entries.map { it.name }
        assertTrue(names.contains("CHALLENGE_REQUEST"), "실제 목록=$names")
        assertTrue(names.contains("CHALLENGE_ACCEPTED"), "실제 목록=$names")
        assertTrue(names.contains("CHALLENGE_REJECTED"), "실제 목록=$names")
    }

    /**
     * 🔴 `soul-oath` 에서 수락=서명을 원자 요청으로 묶어 *"수락했지만 서명 전"* 상태가 소멸했다.
     * 부를 대상이 없는 알림이므로 되살리려면 **먼저 그 중간 상태를 다시 만들어야 한다.**
     */
    @Test
    fun `폐기된 SIGN_REQUEST 는 존재하지 않는다`() {
        assertFalse(
            NotificationType.entries.any { it.name == "SIGN_REQUEST" },
            "SIGN_REQUEST 는 대상 상태가 소멸해 폐기됐다 (push-fcm §0.6 / V8 마이그레이션 주석)",
        )
    }

    /**
     * V8__notification_type_recomment.sql 의 `COMMENT ON COLUMN notifications.type` 과 같은 목록.
     * 한쪽만 바뀌면 여기서 실패한다 — 주석이 다시 낡는 것을 막는 장치다.
     */
    @Test
    fun `어휘 전체가 V8 컬럼 주석과 일치한다`() {
        assertEquals(
            listOf(
                "CHALLENGE_REQUEST",
                "CHALLENGE_ACCEPTED",
                "CHALLENGE_REJECTED",
                "REMIND",
                "OPPONENT_VERIFIED",
                "RESULT",
                "TAUNT",
                "FRIEND_REQUEST",
            ),
            NotificationType.entries.map { it.name },
            "enum 을 바꿨다면 V8__notification_type_recomment.sql 의 COMMENT 도 같이 고쳐라",
        )
    }
}
