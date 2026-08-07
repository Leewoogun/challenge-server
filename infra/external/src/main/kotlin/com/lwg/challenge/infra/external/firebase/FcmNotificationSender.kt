package com.lwg.challenge.infra.external.firebase

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.lwg.challenge.domain.notification.NotificationSender
import com.lwg.challenge.domain.notification.PushMessage
import com.lwg.challenge.domain.notification.SendResult
import org.slf4j.LoggerFactory
import com.google.firebase.messaging.Notification as FcmNotification

/**
 * firebase-admin 기반 실제 발송기 (push-fcm T-B4).
 *
 * 계약 §0.5 대로 **`notification` + `data` 혼합**으로 보낸다:
 * - `notification` 이 있어야 앱이 백그라운드일 때 **시스템이 자동 표시**한다.
 * - `data` 가 있어야 나중에 딥링크가 붙을 때 라우팅 정보가 이미 실려 있다 (서버 변경 0).
 *
 * 🔴 **어떤 실패도 예외로 새지 않는다** ([NotificationSender] 규약). 호출부는 `AFTER_COMMIT`
 * 리스너라 여기서 예외가 나가면 이미 성공한 챌린지 요청 뒤에 스택트레이스만 남는다.
 */
class FcmNotificationSender(
    private val messaging: FirebaseMessaging,
) : NotificationSender {

    private val log = LoggerFactory.getLogger(FcmNotificationSender::class.java)

    override fun send(fcmToken: String, message: PushMessage): SendResult {
        val fcmMessage = Message.builder()
            .setToken(fcmToken)
            .setNotification(
                FcmNotification.builder()
                    .setTitle(message.title)
                    .setBody(message.body)
                    .build(),
            )
            .putData(DATA_KEY_TYPE, message.type.name)
            .apply {
                // ⚠️ putData 는 null 값을 허용하지 않는다. referenceId 가 없으면 키를 아예 넣지 않는다 —
                // 빈 문자열을 넣으면 모바일이 "" 를 파싱하려다 실패한다.
                message.referenceId?.let { putData(DATA_KEY_CHALLENGE_ID, it.toString()) }
            }
            .build()

        return try {
            val messageId = messaging.send(fcmMessage)
            log.debug("푸시 발송 성공: type={}, messageId={}", message.type, messageId)
            SendResult.SENT
        } catch (e: FirebaseMessagingException) {
            classify(e, message)
        } catch (e: Exception) {
            // 네트워크·초기화·그 밖의 런타임 실패. 토큰 탓이 아니므로 정리하지 않는다.
            log.warn("푸시 발송 실패 (type={}): {}", message.type, e.message)
            SendResult.FAILED
        }
    }

    private fun classify(e: FirebaseMessagingException, message: PushMessage): SendResult {
        val result = resultFor(e.messagingErrorCode)
        if (result == SendResult.INVALID_TOKEN) {
            log.info("죽은 FCM 토큰 감지 (code={}, type={}). 토큰을 정리한다.", e.messagingErrorCode, message.type)
        } else {
            log.warn("푸시 발송 실패 (code={}, type={}): {}", e.messagingErrorCode, message.type, e.message)
        }
        return result
    }

    companion object {
        /** 계약 §3 페이로드 규약. ⚠️ FCM `data` 는 값이 전부 문자열로 도착한다. */
        const val DATA_KEY_TYPE = "type"
        const val DATA_KEY_CHALLENGE_ID = "challengeId"

        /**
         * FCM 에러 코드를 **토큰을 정리해야 하는가**로 갈라낸다. 이 클래스에서 가장 중요한 판단이다.
         *
         * - [MessagingErrorCode.UNREGISTERED] — 앱 삭제·데이터 삭제·토큰 만료. 토큰이 죽었다.
         * - [MessagingErrorCode.INVALID_ARGUMENT] — 토큰 형식 자체가 잘못됐다.
         *
         * 🔴 **이 둘만 정리 대상이다.** `UNAVAILABLE`/`INTERNAL` 같은 일시 장애에서 토큰을 지우면
         * **멀쩡한 사용자의 알림을 서버가 스스로 끊고**, 사용자는 재로그인 전까지 복구되지 않는다.
         * 반대로 죽은 토큰을 안 지우면 그 사용자에게 가는 이후 모든 발송이 영구 실패한다.
         * 양쪽 다 조용히 진행되는 사고라 [FcmNotificationSenderTest] 로 고정한다.
         *
         * ⚠️ `null` 은 FCM 이 코드를 주지 않은 경우다 — 토큰 탓이라는 근거가 없으므로 **정리하지 않는다.**
         *
         * 순수 함수로 분리한 이유: `FirebaseMessagingException` 은 공개 생성자가 없어 테스트에서
         * 만들 수 없다. **판단 로직만 떼어 놓으면 Firebase 타입 없이 전수 검증할 수 있다.**
         */
        fun resultFor(errorCode: MessagingErrorCode?): SendResult = when (errorCode) {
            MessagingErrorCode.UNREGISTERED,
            MessagingErrorCode.INVALID_ARGUMENT,
            -> SendResult.INVALID_TOKEN

            else -> SendResult.FAILED
        }
    }
}
