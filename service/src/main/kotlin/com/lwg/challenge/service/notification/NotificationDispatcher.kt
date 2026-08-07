package com.lwg.challenge.service.notification

import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.notification.Notification
import com.lwg.challenge.domain.notification.NotificationMessages
import com.lwg.challenge.domain.notification.NotificationRepository
import com.lwg.challenge.domain.notification.NotificationSender
import com.lwg.challenge.domain.notification.PushMessage
import com.lwg.challenge.domain.notification.SendResult
import com.lwg.challenge.domain.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * [ChallengeNotificationEvent] 를 받아 `notifications` row 를 남기고 푸시를 보낸다 (push-fcm T-B5).
 *
 * ## 🔴 두 어노테이션이 **함께** 있어야 한다
 *
 * ```kotlin
 * @TransactionalEventListener(phase = AFTER_COMMIT)   // 커밋 후에 실행
 * @Transactional(propagation = REQUIRES_NEW)          // 새 트랜잭션에서 저장
 * ```
 *
 * **`AFTER_COMMIT` 리스너는 원 트랜잭션이 이미 끝난 뒤 실행된다.** 그 상태에서 `save` 를 부르면
 * 참여할 트랜잭션이 없어 **저장이 예외 없이 조용히 사라진다.** `REQUIRES_NEW` 가 새 트랜잭션을
 * 열어야 비로소 커밋된다. 계약 §0.3 이 *"이 프로젝트가 반복해서 겪은 '테스트는 초록인데 실제로는
 * 안 됨' 계열"* 이라고 못박은 지점이다.
 *
 * ⚠️ 둘 중 하나만 지우면 **컴파일도 되고 테스트도 초록인데 운영에서 row 가 안 쌓인다.**
 * `NotificationDispatcherWiringTest` 가 어노테이션 존재 자체를 고정한다.
 *
 * ## 왜 예외를 삼키는가 — 여기서 던지면 **성공한 요청이 실패로 보인다**
 *
 * `AFTER_COMMIT` 콜백에서 던진 예외는 `AbstractPlatformTransactionManager.processCommit` 의
 * `triggerAfterCommit` 을 타고 **커밋을 호출한 쪽으로 전파된다.** 트랜잭션은 이미 커밋됐으므로
 * 챌린지는 정상 생성됐는데 **클라이언트는 500 을 받는다.** 사용자는 실패로 알고 다시 시도하고,
 * 그러면 중복 오류를 만난다 — 알림 하나 때문에 기능이 망가지는 정확한 경로다.
 *
 * ## ⚠️ 이 방어의 **정확한 범위** — [dispatch] 내부 예외까지다
 *
 * 🔴 **"모든 예외 전파를 막는다"가 아니다.** try/catch 가 메서드 **본문 안**에 있고,
 * `REQUIRES_NEW` 커밋은 그 **밖**(프록시)에서 일어나기 때문이다:
 * ```
 * 프록시: 트랜잭션 시작
 *   본문:  try { dispatch(event) } catch (e: Exception) { log }   ← 여기까지만 잡힌다
 * 프록시: commit()                                                ← 여기서 터지면 못 잡는다
 * ```
 * `save()` 가 영속성 컨텍스트를 오염시킨 뒤 catch 가 삼키면, **커밋 시점에 터지는 예외는
 * 이 catch 밖**이다. 예를 들어 수신자가 커밋 직후 삭제돼 `notifications.user_id` FK 가
 * 깨지는 경우가 그 경로다.
 *
 * **이 잔여 구간은 막지 않았고, 확인된 적도 없다.** 막으려면 트랜잭션 빈을 분리해
 * (`@Transactional` 을 다른 빈에 두고 그 호출을 try 로 감싸) 커밋을 try 안으로 넣어야 하는데,
 * **관측된 적 없는 시나리오를 위해 빈과 간접 계층을 늘리는 구조 변경**이라 하지 않았다.
 * 실제로 발생하면 그때 이 주석을 근거로 바꾸면 된다.
 *
 * [NotificationDispatcherTest] 의 "터져도 예외가 새어나가지 않는다" 2건은 fake 가 던지고
 * **트랜잭션이 없는** 경로라, 위 잔여 구간을 검증하지 않는다.
 *
 * ## ⚠️ `runCatching` 이 아니라 `catch (e: Exception)` 인 이유
 *
 * Kotlin `runCatching` 은 `catch (e: Throwable)` 이라 **`OutOfMemoryError`·`StackOverflowError`
 * 까지 삼킨다.** 알림 하나 때문에 JVM 이 죽어야 할 상황이 로그 한 줄로 묻히고 정상 반환된다.
 *
 * 처음엔 `runCatching` 으로 썼다가 되돌린 것이다 — **삼키는 범위를 넓히는 게 아니라 좁히는 변경**이라
 * 치명적 신호가 정상 전파된다.
 */
@Component
class NotificationDispatcher(
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
    private val notificationSender: NotificationSender,
) {

    private val log = LoggerFactory.getLogger(NotificationDispatcher::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onChallengeNotification(event: ChallengeNotificationEvent) {
        try {
            dispatch(event)
        } catch (e: Exception) {
            // 여기서 새어나가면 위 KDoc 의 "성공한 요청이 500" 이 된다.
            log.error(
                "알림 처리 실패 (type={}, challengeId={}, recipientId={}) — 요청 자체는 이미 성공했다",
                event.type,
                event.challengeId,
                event.recipientId,
                e,
            )
        }
    }

    private fun dispatch(event: ChallengeNotificationEvent) {
        val actorNickname = userRepository.findById(event.actorId)?.nickname
            ?: NotificationMessages.UNKNOWN_NICKNAME

        val message = NotificationMessages.of(event.type, actorNickname)
        if (message == null) {
            // 문구가 없는 타입 = 아직 선행 feature 가 없는 타입. 빈 알림을 만들어 내보내지 않는다.
            log.warn("문구가 정의되지 않은 알림 타입이라 건너뜁니다: {}", event.type)
            return
        }

        // 🔴 저장이 먼저다. 발송이 실패하거나 건너뛰어도 row 는 남아야 한다 (계약 §3) —
        // 알림 목록 화면이 붙었을 때 "그때 알림이 왔었다" 가 보여야 하고, 그 사실은
        // 푸시가 실제로 떴는지와 무관하다.
        notificationRepository.save(
            Notification(
                id = null,
                userId = event.recipientId,
                type = event.type,
                title = message.title,
                body = message.body,
                referenceId = event.challengeId,
                isRead = false,
                createdAt = KstTime.now(),
            ),
        )

        val recipient = userRepository.findById(event.recipientId)
        val token = recipient?.fcmToken
        if (token.isNullOrBlank()) {
            // 알림 권한 거부 · 로그아웃 · 아직 토큰 미등록. 정상 상태다.
            log.debug("수신자에게 FCM 토큰이 없어 발송을 건너뜁니다: recipientId={}", event.recipientId)
            return
        }

        val result = notificationSender.send(
            fcmToken = token,
            message = PushMessage(
                title = message.title,
                body = message.body,
                type = event.type,
                referenceId = event.challengeId,
            ),
        )

        if (result == SendResult.INVALID_TOKEN) {
            // 죽은 토큰을 남겨두면 이 사용자에게 가는 이후 모든 발송이 영구 실패한다 (계약 §3).
            userRepository.updateFcmToken(event.recipientId, null)
            log.info("죽은 FCM 토큰을 정리했습니다: recipientId={}", event.recipientId)
        }
    }
}
