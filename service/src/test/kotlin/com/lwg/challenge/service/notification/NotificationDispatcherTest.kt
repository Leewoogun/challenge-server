package com.lwg.challenge.service.notification

import com.lwg.challenge.domain.notification.Notification
import com.lwg.challenge.domain.notification.NotificationRepository
import com.lwg.challenge.domain.notification.NotificationSender
import com.lwg.challenge.domain.notification.NotificationType
import com.lwg.challenge.domain.notification.PushMessage
import com.lwg.challenge.domain.notification.SendResult
import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.service.user.FakeUserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

/**
 * [NotificationDispatcher] 단위 테스트 (push-fcm T-B5/T-B6).
 *
 * 커버하는 수용 기준:
 * - 발송 3종 각각 `notifications` row 가 `user_id`/`type`/`title`/`body`/`reference_id` 와 함께 저장
 * - 수신자 `fcm_token` 이 NULL 이어도 **row 는 저장되고** 발송만 생략
 * - FCM 이 `UNREGISTERED`/`INVALID_ARGUMENT` 를 주면 해당 토큰을 NULL 로 정리
 * - 키가 없어(no-op) 발송이 건너뛰어져도 row 저장은 그대로
 *
 * ⚠️ **덮지 못하는 것**: `AFTER_COMMIT` 타이밍과 `REQUIRES_NEW` 트랜잭션 경계. 이 테스트에는
 * 트랜잭션이 없어 *"커밋 후에만 실행된다"* 나 *"새 트랜잭션에서 저장된다"* 를 증명하지 않는다.
 * 어노테이션이 붙어 있다는 사실만 [NotificationDispatcherWiringTest] 가 고정하고, 실제 동작은
 * Testcontainers 통합 테스트(현재 skip)에서만 확인 가능하다.
 */
class NotificationDispatcherTest {

    private val notifications = RecordingNotificationRepository()
    private val users = FakeUserRepository()
    private val sender = StubNotificationSender()

    private val dispatcher = NotificationDispatcher(notifications, users, sender)

    private companion object {
        const val RECIPIENT = 1L
        const val ACTOR = 2L
        const val CHALLENGE_ID = 77L
        const val TOKEN = "recipient-device-token"
    }

    private fun seed(recipientToken: String? = TOKEN) {
        users.put(user(RECIPIENT, "받는이", recipientToken))
        users.put(user(ACTOR, "지연", null))
    }

    private fun event(type: NotificationType = NotificationType.CHALLENGE_REQUEST) =
        ChallengeNotificationEvent(
            recipientId = RECIPIENT,
            actorId = ACTOR,
            type = type,
            challengeId = CHALLENGE_ID,
        )

    // ─── row 저장 ────────────────────────────────────────────────

    @Test
    fun `알림 row 가 수신자 타입 문구 참조id 와 함께 저장된다`() {
        seed()

        dispatcher.onChallengeNotification(event())

        val saved = notifications.all().single()
        assertEquals(RECIPIENT, saved.userId, "row 는 **받는 사람** 앞으로 저장된다")
        assertEquals(NotificationType.CHALLENGE_REQUEST, saved.type)
        assertEquals(CHALLENGE_ID, saved.referenceId)
        assertEquals(false, saved.isRead)
        assertTrue(saved.body.contains("지연"), "본문에 상대 닉네임이 들어간다: ${saved.body}")
    }

    @Test
    fun `발송 3종 모두 row 를 남긴다`() {
        seed()

        dispatcher.onChallengeNotification(event(NotificationType.CHALLENGE_REQUEST))
        dispatcher.onChallengeNotification(event(NotificationType.CHALLENGE_ACCEPTED))
        dispatcher.onChallengeNotification(event(NotificationType.CHALLENGE_REJECTED))

        assertEquals(
            listOf(
                NotificationType.CHALLENGE_REQUEST,
                NotificationType.CHALLENGE_ACCEPTED,
                NotificationType.CHALLENGE_REJECTED,
            ),
            notifications.all().map { it.type },
        )
    }

    /** 저장된 문구와 발송된 문구가 같아야 한다 — 다르면 목록 화면과 푸시가 어긋나 버그로 보인다. */
    @Test
    fun `저장 문구와 발송 문구가 동일하다`() {
        seed()

        dispatcher.onChallengeNotification(event())

        val saved = notifications.all().single()
        val sent = sender.sent.single().second
        assertEquals(saved.title, sent.title)
        assertEquals(saved.body, sent.body)
    }

    // ─── 토큰이 없을 때 ──────────────────────────────────────────

    /**
     * 🔴 수용 기준: *"수신자의 `fcm_token` 이 NULL 이어도 `notifications` row 는 저장된다."*
     *
     * 알림 권한 거부·로그아웃 상태가 챌린지 기능을 막으면 안 되고, 알림 목록 화면이 붙었을 때
     * 과거 알림이 있어야 한다.
     */
    @Test
    fun `수신자에게 토큰이 없어도 row 는 저장되고 발송만 건너뛴다`() {
        seed(recipientToken = null)

        dispatcher.onChallengeNotification(event())

        assertEquals(1, notifications.all().size, "토큰이 없다고 row 까지 사라지면 안 된다")
        assertTrue(sender.sent.isEmpty(), "토큰이 없는데 발송을 시도했다")
    }

    @Test
    fun `토큰이 공백 문자열이어도 발송하지 않는다`() {
        seed(recipientToken = "   ")

        dispatcher.onChallengeNotification(event())

        assertEquals(1, notifications.all().size)
        assertTrue(sender.sent.isEmpty())
    }

    // ─── 죽은 토큰 정리 ──────────────────────────────────────────

    /** 🔴 수용 기준: `UNREGISTERED`/`INVALID_ARGUMENT` 응답 시 토큰을 NULL 로 정리한다. */
    @Test
    fun `발송이 INVALID_TOKEN 이면 수신자 토큰을 NULL 로 정리한다`() {
        seed()
        sender.result = SendResult.INVALID_TOKEN

        dispatcher.onChallengeNotification(event())

        assertNull(users.fcmTokenOf(RECIPIENT), "죽은 토큰을 남기면 이후 모든 발송이 영구 실패한다")
    }

    /** 🔴 반대 방향 — 일시 실패에 토큰을 지우면 멀쩡한 사용자의 알림이 끊긴다. */
    @Test
    fun `발송이 FAILED 여도 토큰은 살려둔다`() {
        seed()
        sender.result = SendResult.FAILED

        dispatcher.onChallengeNotification(event())

        assertEquals(TOKEN, users.fcmTokenOf(RECIPIENT), "일시 실패는 토큰 탓이 아니다")
    }

    /** 키가 없어 no-op 인 상태(현재 기본값)에서도 토큰은 그대로여야 한다. */
    @Test
    fun `발송이 SKIPPED 여도 토큰은 살려두고 row 는 저장된다`() {
        seed()
        sender.result = SendResult.SKIPPED

        dispatcher.onChallengeNotification(event())

        assertEquals(TOKEN, users.fcmTokenOf(RECIPIENT))
        assertEquals(1, notifications.all().size)
    }

    // ─── 실패 격리 ───────────────────────────────────────────────

    /**
     * 🔴 **여기서 예외가 새면 이미 성공한 챌린지 요청이 500 으로 보인다.**
     *
     * `AFTER_COMMIT` 콜백의 예외는 커밋 호출자에게 전파된다. 트랜잭션은 이미 커밋됐으므로
     * 챌린지는 만들어졌는데 클라이언트는 실패로 알고 재시도하고, 그러면 중복 오류를 만난다.
     */
    @Test
    fun `저장이 터져도 예외가 새어나가지 않는다`() {
        seed()
        notifications.failOnSave = true

        assertDoesNotThrow { dispatcher.onChallengeNotification(event()) }
    }

    @Test
    fun `발송기가 터져도 예외가 새어나가지 않는다`() {
        seed()
        sender.throwOnSend = true

        assertDoesNotThrow { dispatcher.onChallengeNotification(event()) }

        assertEquals(1, notifications.all().size, "발송이 터져도 이미 저장된 row 는 남는다")
    }

    /**
     * 🔴 **`Error` 는 삼키지 않는다** — 잡는 범위를 넓히는 게 아니라 **좁히는** 쪽이다.
     *
     * 처음에 `runCatching` 으로 썼다가 되돌린 **실제 결함**을 고정한다. `runCatching` 은
     * `catch (e: Throwable)` 이라 `OutOfMemoryError`·`StackOverflowError` 까지 삼켜
     * **JVM 이 죽어야 할 상황을 로그 한 줄로 묻고 정상 반환**한다.
     *
     * `runCatching` 은 짧고 관용적이라 되돌아가기 쉬운 자리다. 그래서 테스트로 막는다.
     */
    @Test
    fun `Error 는 삼키지 않고 전파한다`() {
        seed()
        sender.throwErrorOnSend = true

        assertThrows<OutOfMemoryError> { dispatcher.onChallengeNotification(event()) }
    }

    // ─── 문구가 없는 타입 ────────────────────────────────────────

    /**
     * 아직 선행 feature 가 없어 문구가 정의되지 않은 타입. 빈 알림을 만들어 내보내는 대신
     * **아무것도 하지 않는다** — 사용자에게 깨진 알림이 가는 것보다 안 가는 편이 낫다.
     */
    @Test
    fun `문구가 없는 타입은 저장도 발송도 하지 않는다`() {
        seed()

        dispatcher.onChallengeNotification(event(NotificationType.RESULT))

        assertTrue(notifications.all().isEmpty())
        assertTrue(sender.sent.isEmpty())
    }

    // ─── 닉네임 조회 실패 ────────────────────────────────────────

    @Test
    fun `상대를 찾을 수 없어도 대체 닉네임으로 진행한다`() {
        // actor 를 넣지 않는다 — 탈퇴 등으로 사라진 경우.
        users.put(user(RECIPIENT, "받는이", TOKEN))

        dispatcher.onChallengeNotification(event())

        val saved = notifications.all().single()
        assertTrue(saved.body.contains("(알 수 없음)"), "실제 본문=${saved.body}")
    }

    private fun user(id: Long, nickname: String, token: String?) = User(
        id = id,
        kakaoId = 1000L + id,
        nickname = nickname,
        profileImageUrl = null,
        phoneNumber = null,
        phoneVerified = false,
        fcmToken = token,
        status = UserStatus.ACTIVE,
        createdAt = LocalDateTime.of(2026, 8, 6, 0, 0),
        updatedAt = LocalDateTime.of(2026, 8, 6, 0, 0),
    )
}

/** 저장된 알림을 그대로 모아 두는 fake. [failOnSave] 로 저장 실패를 재현한다. */
private class RecordingNotificationRepository : NotificationRepository {

    private val store = mutableListOf<Notification>()
    var failOnSave = false

    fun all(): List<Notification> = store.toList()

    override fun save(notification: Notification): Notification {
        if (failOnSave) throw IllegalStateException("저장 실패 재현")
        val saved = notification.copy(id = (store.size + 1).toLong())
        store += saved
        return saved
    }

    /** 목록 조회 전용 — 발송 경로가 수신함을 읽으면 그 자체가 버그다 (`NotificationQueryServiceTest` 소관). */
    override fun findPageByUserId(userId: Long, cursor: Long?, limit: Int): List<Notification> =
        error("발송 경로는 알림을 조회하지 않는다")

    /** 뱃지 전용 — 발송 경로는 안 읽은 개수를 세지 않는다 (`NotificationQueryServiceTest` 소관). */
    override fun countUnreadByUserId(userId: Long): Long =
        error("발송 경로는 안 읽은 개수를 세지 않는다")

    /** 읽음 처리 전용 — 발송 경로는 읽음을 건드리지 않는다 (`NotificationCommandServiceTest` 소관). */
    override fun markAllAsReadByUserId(userId: Long): Int =
        error("발송 경로는 읽음 처리를 하지 않는다")

    /** 탈퇴 전용 — 이 테스트가 다루는 경로가 아니다 (`WithdrawalServiceTest` 소관). */
    override fun deleteAllByUserId(userId: Long): Int = 0
}

/** 발송 요청을 기록하는 stub. [result] 로 FCM 응답을, [throwOnSend] 로 발송기 자체의 사고를 재현한다. */
private class StubNotificationSender : NotificationSender {

    val sent = mutableListOf<Pair<String, PushMessage>>()
    var result: SendResult = SendResult.SENT
    var throwOnSend = false

    /** `Error` 를 삼키지 않는지 확인하기 위한 스위치. `Exception` 이 아니라 `Error` 여야 한다. */
    var throwErrorOnSend = false

    override fun send(fcmToken: String, message: PushMessage): SendResult {
        if (throwErrorOnSend) throw OutOfMemoryError("치명적 오류 재현")
        if (throwOnSend) throw IllegalStateException("발송기 사고 재현")
        sent += fcmToken to message
        return result
    }
}
