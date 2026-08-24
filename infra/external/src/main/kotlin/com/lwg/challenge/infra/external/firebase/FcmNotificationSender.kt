package com.lwg.challenge.infra.external.firebase

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
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
 * 여기에 `android` 블록이 더해진다 (push-deeplink T-B1) — 트레이 중복 제거용 `notification.tag`
 * 와 `priority`. **`notification`/`data` 블록 자체는 그대로다.** [androidConfigFor] 참조.
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
            .setAndroidConfig(androidConfigFor(message.referenceId))
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

        /** 알림 트레이 묶음 키 접두사. `challenge-{referenceId}`. */
        const val TAG_PREFIX = "challenge-"

        /**
         * 같은 대상에 대한 알림을 트레이에서 **하나로 덮어쓰기** 위한 tag (push-deeplink 판정 3).
         *
         * "도전장이 왔다" 뒤에 "수락됐다" 가 따로 쌓이면 트레이만 지저분해진다. 사용자가 알아야 할
         * 것은 **최신 상태 하나**다. 앱이 직접 그리는 포그라운드 경로는 이미 `challengeId` 를 알림
         * id 로 써서 덮어쓰고 있으므로, 이 tag 는 **백그라운드 동작을 포그라운드에 맞추는** 것이다.
         *
         * 🔴 **[referenceId] 가 `null` 이면 tag 를 붙이지 않는다 (`null` 반환).**
         * `"challenge-null"` 로 뭉치면 **서로 무관한 알림들이 한 덩어리가 되어 서로를 덮어쓴다** —
         * 알림이 예외도 로그도 없이 사라지는 사고다. `putData` 가 [DATA_KEY_CHALLENGE_ID] 를
         * 조건부로 넣는 것과 **같은 이유·같은 판단**이다.
         *
         * ⚠️ 현재 3종은 전부 `challenges.id` 를 참조하므로 실제로는 항상 붙지만, 그건 **오늘의
         * 사실이지 규약이 아니다** (계약 오픈 이슈 3 — `reference_id` 를 `challenge_id` 로 못 박지
         * 않는다). `referenceId` 없는 타입이 붙는 순간 이 분기가 유일한 방어가 된다.
         *
         * `resultFor` 와 같은 이유로 순수 함수다 — Firebase 타입 없이 전수 검증할 수 있다.
         */
        fun tagFor(referenceId: Long?): String? = referenceId?.let { "$TAG_PREFIX$it" }

        /**
         * `android` 블록. **[Message] 의 `notification`/`data` 블록은 건드리지 않는다** — 계약
         * §3 페이로드의 shape 이 바뀌면 모바일 파서와 `push-fcm` 의 `confirmed` 계약이 동시에 깨진다.
         *
         * ⚠️ `android.notification` 에 **[tagFor] 결과만** 넣는다. FCM 은 `android.notification` 의
         * 필드 중 **설정된 것만** 공통 `notification` 을 덮어쓰므로, title/body 를 비워 두면 위에서
         * 만든 공통 `notification` 이 그대로 표시된다. (직렬화에서 미설정 필드는 아예 빠진다 —
         * `FcmNotificationSenderTest` 가 JSON 원문으로 고정한다.)
         *
         * ## priority = HIGH 로 두는 이유
         *
         * **오늘 기준으로 동작이 바뀌지 않는다.** FCM 은 `notification` 을 포함한 메시지를 이미
         * high 로 취급하고(data-only 만 normal 이 기본), 우리 페이로드는 혼합이다(계약 §0.5).
         * 즉 이건 **새 동작을 켜는 게 아니라 현재의 실효 동작을 명시로 고정**하는 것이다 —
         * 나중에 페이로드가 data-only 로 바뀌어도(push-deeplink 판정 4 에서 **보류**, 폐기 아님)
         * 즉시성이 조용히 normal 로 떨어지지 않는다.
         *
         * 근거: 현재 3종은 전부 **사용자 행동이 즉시 유발한 1:1 알림**이라 지연될수록 무의미해진다.
         * 배터리 정책상 high 남용이 문제가 되는 것은 **주기·배치·마케팅 발송**인데 그런 경로가 없다.
         *
         * ⚠️ **`RESULT`(자정 판정 배치)·`REMIND`(마감 1시간 전 배치)가 붙을 때 이 판단을 재검토할 것.**
         * 자정 배치가 전 사용자 기기를 한꺼번에 Doze 에서 깨우는 것은 위에서 말한 남용 패턴 그 자체다.
         * 지금 타입별 분기를 미리 만들지는 않는다 — 해당 타입이 없어 근거를 정할 수 없다.
         */
        fun androidConfigFor(referenceId: Long?): AndroidConfig =
            AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .apply {
                    tagFor(referenceId)?.let {
                        setNotification(AndroidNotification.builder().setTag(it).build())
                    }
                }
                .build()

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
