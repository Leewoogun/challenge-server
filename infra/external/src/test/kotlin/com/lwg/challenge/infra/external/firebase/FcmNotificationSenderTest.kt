package com.lwg.challenge.infra.external.firebase

import com.google.firebase.messaging.MessagingErrorCode
import com.lwg.challenge.domain.notification.SendResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * FCM 에러 코드 → [SendResult] 분류 (push-fcm T-B4, 계약 §3 "발송 실패 처리").
 *
 * ## 왜 이 판단만 따로 테스트하는가
 *
 * 양방향으로 조용한 사고가 나는 지점이라서다:
 * - **죽은 토큰을 안 지우면** 그 사용자에게 가는 이후 **모든** 발송이 영구 실패한다.
 * - **살아있는 토큰을 지우면** 서버가 멀쩡한 사용자의 알림을 스스로 끊고, 사용자는
 *   재로그인 전까지 복구되지 않는다.
 *
 * 둘 다 예외도 에러 로그도 없이 진행된다. 그래서 **전수로** 고정한다.
 *
 * ⚠️ `FirebaseMessagingException` 은 공개 생성자가 없어 테스트에서 만들 수 없다. 그래서
 * 프로덕션 코드에서 판단부를 `resultFor(errorCode)` 순수 함수로 떼어 놓았고 여기서 그걸 부른다.
 * **`send()` 의 나머지(메시지 빌드·호출·catch)는 이 테스트가 덮지 않는다** — 실제 발송 검증은
 * 사용자가 키를 발급한 뒤 실기로 한다 (spec T-U3).
 */
class FcmNotificationSenderTest {

    /** 앱 삭제·데이터 삭제·토큰 만료. 토큰이 죽었다 → 정리 대상. */
    @Test
    fun `UNREGISTERED 는 INVALID_TOKEN`() {
        assertEquals(
            SendResult.INVALID_TOKEN,
            FcmNotificationSender.resultFor(MessagingErrorCode.UNREGISTERED),
        )
    }

    /** 토큰 형식 자체가 잘못됐다 → 정리 대상. */
    @Test
    fun `INVALID_ARGUMENT 는 INVALID_TOKEN`() {
        assertEquals(
            SendResult.INVALID_TOKEN,
            FcmNotificationSender.resultFor(MessagingErrorCode.INVALID_ARGUMENT),
        )
    }

    /**
     * 🔴 **나머지 코드에서 토큰을 지우면 안 된다.**
     *
     * `UNAVAILABLE`/`INTERNAL` 은 FCM 쪽 일시 장애다. 여기서 토큰을 NULL 로 만들면
     * 장애 한 번에 사용자 다수의 알림이 영구히 끊긴다.
     */
    @Test
    fun `정리 대상 2종을 뺀 나머지 코드는 전부 FAILED`() {
        val cleanupCodes = setOf(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT)

        val misclassified = MessagingErrorCode.entries
            .filterNot { it in cleanupCodes }
            .filter { FcmNotificationSender.resultFor(it) != SendResult.FAILED }

        assertEquals(
            emptyList<MessagingErrorCode>(),
            misclassified,
            "일시 장애 코드를 토큰 문제로 오분류하면 멀쩡한 사용자의 알림이 끊긴다",
        )
    }

    /** FCM 이 코드를 주지 않은 경우 — 토큰 탓이라는 근거가 없으므로 정리하지 않는다. */
    @Test
    fun `errorCode 가 null 이면 FAILED`() {
        assertEquals(SendResult.FAILED, FcmNotificationSender.resultFor(null))
    }
}
