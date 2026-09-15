package com.lwg.challenge.domain.notification

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [NotificationMessages] — 문구가 있는 타입과 없는 타입의 경계 (push-fcm T-B4).
 *
 * ⚠️ **문구 문자열 자체는 단언하지 않는다.** 계약 §3 에서 아직 미확정이고 디자인 확인 후 바뀐다
 * (반말/존댓말 축 미결). 지금 문자열을 테스트에 박으면 **문구를 바꿀 때마다 테스트가 깨지는데
 * 그 깨짐이 아무것도 알려주지 않는다.**
 *
 * 대신 **바뀌지 않을 성질**만 고정한다:
 * - 발송 가능한 타입은 문구가 있다
 * - 아직 트리거가 없는 타입은 null 이다 (= 발송하지 않는다)
 * - 본문에 상대 닉네임이 들어간다
 */
class NotificationMessagesTest {

    /**
     * **오늘 실제로 발송되는 타입.** 이 목록이 곧 발송 스위치의 상태다 —
     * push-fcm 계약 §0.6.1 이 *"문구 추가 = 발송 개시"* 로 규정했기 때문이다.
     *
     * 🔴 **여기에 타입을 추가하기 전에 모바일 통지·회신이 선행해야 한다.**
     * `OPPONENT_VERIFIED` 는 2026-08-14 에 그 절차를 밟고 들어왔다
     * (challenge-verification 계약 §0.5 — `verif-mobile` 이 `PushEvent.OpponentVerified` 와
     * 딥링크를 반영하고 회신한 뒤에 열었다).
     */
    private val inScope = listOf(
        NotificationType.CHALLENGE_REQUEST,
        NotificationType.CHALLENGE_ACCEPTED,
        NotificationType.CHALLENGE_REJECTED,
        NotificationType.OPPONENT_VERIFIED,
        // challenge-forfeit T-B4 (2026-09-05). forfeit-mobile 이 PushEvent·딥링크·목록 비탭
        // 배선을 마치고 숫자와 함께 회신한 뒤에 열었다 — OPPONENT_VERIFIED 와 같은 절차다.
        NotificationType.CHALLENGE_FORFEITED,
    )

    @Test
    fun `발송 가능한 타입은 제목과 본문이 있다`() {
        inScope.forEach { type ->
            val message = NotificationMessages.of(type, "지연")
            assertNotNull(message, "$type 문구가 없다")
            assertTrue(message!!.title.isNotBlank(), "$type 제목이 비었다")
            assertTrue(message.body.isNotBlank(), "$type 본문이 비었다")
        }
    }

    @Test
    fun `본문에 상대 닉네임이 들어간다`() {
        inScope.forEach { type ->
            val body = NotificationMessages.of(type, "지연")!!.body
            assertTrue(body.contains("지연"), "$type 본문에 닉네임이 없다: $body")
        }
    }

    /**
     * 🔴 문구가 없으면 **null 을 주고 발송하지 않는다.** 빈 문자열이나 임시 문구를 만들어 내보내면
     * 사용자에게 깨진 알림이 간다. [NotificationDispatcher] 가 null 을 보고 건너뛴다.
     */
    @Test
    fun `아직 트리거가 없는 타입은 null 이다`() {
        val outOfScope = NotificationType.entries.filterNot { it in inScope }

        val unexpected = outOfScope.filter { NotificationMessages.of(it, "지연") != null }

        assertTrue(
            unexpected.isEmpty(),
            "선행 feature 없이 문구만 생긴 타입이 있다 — 발송 경로도 함께 만들었는지 확인해라: $unexpected",
        )
    }

    @Test
    fun `타입 전수가 문구 정의에서 누락되지 않는다`() {
        // when 이 exhaustive 라 컴파일 시점에 보장되지만, 새 타입이 추가될 때
        // null 분기로 슬쩍 들어가는 것을 눈에 띄게 한다.
        NotificationType.entries.forEach { type ->
            val message = NotificationMessages.of(type, "지연")
            if (type in inScope) assertNotNull(message) else assertNull(message)
        }
    }
}
