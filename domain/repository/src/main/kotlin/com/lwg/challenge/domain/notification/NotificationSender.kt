package com.lwg.challenge.domain.notification

/**
 * 푸시 발송 포트 (push-fcm T-B4). 구현체는 `:infra:external` 의
 * `FcmNotificationSender`(firebase-admin) 또는 `NoOpNotificationSender`.
 *
 * ## 🔴 예외를 던지지 않는다
 *
 * `send` 는 **어떤 실패에서도 예외를 던지지 않고 [SendResult] 로 답한다.** 발송은 부수 효과이고
 * 호출부는 커밋이 끝난 뒤(`AFTER_COMMIT`)라, 여기서 예외가 새면 이미 성공한 챌린지 요청 뒤에
 * 정체불명의 스택트레이스만 남는다. **"알림이 안 갔다"는 불편이지만 "챌린지가 안 걸렸다"는
 * 기능 실패다** (계약 §0.3).
 *
 * 도메인이 FCM 을 모르게 하려고 포트를 둔다 — `:service` 는 이 인터페이스만 보고
 * firebase-admin 타입을 import 하지 않는다.
 */
interface NotificationSender {

    /**
     * [fcmToken] 기기로 푸시 1건을 보낸다.
     *
     * @param fcmToken 수신자의 등록 토큰. 호출부가 blank/null 을 걸러서 넘긴다.
     * @return 처리 결과. 절대 예외를 던지지 않는다.
     */
    fun send(fcmToken: String, message: PushMessage): SendResult
}

/**
 * 발송할 푸시 1건 (계약 §3 페이로드 규약).
 *
 * `notification` + `data` 혼합으로 나간다 — `notification` 이 있어야 백그라운드에서 시스템이
 * 자동 표시하고, `data` 가 있어야 나중에 딥링크가 붙을 때 라우팅 정보가 이미 실려 있다.
 *
 * ⚠️ **FCM `data` 는 값이 전부 문자열이다.** [referenceId] 를 숫자로 넣어도 문자열로 도착하므로
 * 모바일이 파싱해야 한다 (계약 §3 표에 명시).
 */
data class PushMessage(
    val title: String,
    val body: String,
    val type: NotificationType,
    /** 현재 3종은 전부 `challenges.id`. `data.challengeId` 로 실린다. */
    val referenceId: Long?,
)

/**
 * 발송 결과.
 *
 * [INVALID_TOKEN] 만 호출부가 **행동을 바꿔야 하는** 값이다 — 나머지는 로깅 대상이다.
 */
enum class SendResult {

    /** FCM 이 접수했다. */
    SENT,

    /** 발송기가 비활성이라 건너뛰었다 (서비스 계정 키 부재 등). **정상 상태다.** */
    SKIPPED,

    /**
     * 🔴 토큰이 죽었다 — FCM `UNREGISTERED` / `INVALID_ARGUMENT`.
     *
     * 앱 삭제·데이터 삭제·토큰 만료로 생긴다. **호출부는 이 토큰을 `NULL` 로 정리해야 한다.**
     * 안 하면 그 사용자에게 가는 이후 모든 발송이 영구히 실패한다 (계약 §3).
     */
    INVALID_TOKEN,

    /** 일시적 실패(네트워크·FCM 5xx 등). 토큰은 살아 있으므로 **정리하면 안 된다.** */
    FAILED,
}
