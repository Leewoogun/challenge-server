package com.lwg.challenge.infra.external.firebase

import com.google.api.client.json.gson.GsonFactory
import com.google.firebase.messaging.MessagingErrorCode
import com.lwg.challenge.domain.notification.SendResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    // ---------------------------------------------------------------------------------------
    // android 블록 (push-deeplink T-B1)
    //
    // ⚠️ AndroidConfig 는 공개 getter 가 없다. 그래서 firebase-admin 이 **실제로 전송에 쓰는
    // 직렬화 경로**(@Key + JsonFactory)를 그대로 태워 JSON 원문으로 단언한다. 술어를 다시 쓰는
    // 게 아니라 나가는 바이트를 본다.
    // ---------------------------------------------------------------------------------------

    private fun androidJson(referenceId: Long?): String =
        GsonFactory.getDefaultInstance().toString(FcmNotificationSender.androidConfigFor(referenceId))

    @Test
    fun `referenceId 가 있으면 challenge- 접두사 tag 가 붙는다`() {
        assertEquals("challenge-7", FcmNotificationSender.tagFor(7L))
    }

    /**
     * 🔴 **`"challenge-null"` 이 되면 안 된다.**
     *
     * 그 문자열로 뭉치면 서로 무관한 알림들이 한 덩어리가 되어 서로를 덮어쓴다 — 예외도 로그도
     * 없이 알림이 사라지는 사고다. `putData` 가 `challengeId` 키를 조건부로 넣는 것과 같은 판단.
     */
    @Test
    fun `referenceId 가 null 이면 tag 를 만들지 않는다`() {
        assertNull(FcmNotificationSender.tagFor(null))
    }

    @Test
    fun `tag 가 android notification 에 실린다`() {
        assertEquals("""{"notification":{"tag":"challenge-7"},"priority":"high"}""", androidJson(7L))
    }

    /** tag 가 없으면 `android.notification` 블록 자체가 나가지 않는다 — 빈 tag 를 보내지 않는다. */
    @Test
    fun `referenceId 가 null 이면 android notification 블록이 아예 없다`() {
        assertEquals("""{"priority":"high"}""", androidJson(null))
    }

    /**
     * 🔴 **`android.notification` 에 title/body 가 새어 들어가면 안 된다.**
     *
     * FCM 은 `android.notification` 의 **설정된 필드만** 공통 `notification` 을 덮어쓴다. 여기에
     * 빈 title/body 가 실리면 트레이에 **제목 없는 알림**이 뜨고, 그건 `push-fcm` 의 `confirmed`
     * 계약 §3 문구 3종이 통째로 무력화되는 것이다. tag 하나만 나가는지 원문으로 고정한다.
     */
    @Test
    fun `android notification 은 tag 외에 아무 필드도 싣지 않는다`() {
        val notificationBlock = androidJson(7L).substringAfter(""""notification":""").substringBefore(",\"priority")

        assertEquals(
            """{"tag":"challenge-7"}""",
            notificationBlock,
            "title/body 가 여기 실리면 공통 notification 을 덮어써 계약 §3 문구가 무력화된다",
        )
    }

    /**
     * priority 는 **오늘의 실효 동작을 명시로 고정**한 것이다 — FCM 은 `notification` 을 포함한
     * 메시지를 이미 high 로 취급한다. 나중에 data-only 로 바뀌어도 즉시성이 조용히 normal 로
     * 떨어지지 않게 한다. 배치 타입(`RESULT`/`REMIND`)이 붙을 때 재검토 대상 (프로덕션 KDoc 참조).
     */
    @Test
    fun `priority 는 referenceId 유무와 무관하게 high`() {
        assertEquals(
            listOf(true, true),
            listOf(7L, null).map { """"priority":"high"""" in androidJson(it) },
        )
    }
}
