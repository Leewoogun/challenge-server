package com.lwg.challenge.service.notification

import com.lwg.challenge.domain.notification.Notification
import com.lwg.challenge.domain.notification.NotificationRepository
import com.lwg.challenge.domain.notification.NotificationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * [NotificationCommandService] 단위 테스트 — *"목록을 열면 전부 읽음"* 의 실행 규칙.
 *
 * ## 무엇을 덮는가
 *
 * 1. **갱신 후의 개수를 반환한다** — 정상 경로에서 `0`.
 * 2. 🔴 **그 `0` 이 하드코딩이 아니라 실측이다.** 이 파일의 존재 이유가 사실상 이 한 가지다 —
 *    `mark 가 아무것도 갱신하지 못하면 0 이 아니라 남은 개수가 나온다` 테스트가 그 회귀 장치다.
 * 3. **멱등** — 이미 다 읽은 상태에서 다시 불러도 `0`.
 *
 * ⚠️ **fake 가 흉내낸 것은 `isRead = false` 인 행만 갱신·집계한다는 규칙까지다.** 실제 JPQL 의
 * 벌크 UPDATE 가 같은 조건으로 도는지, 같은 트랜잭션의 COUNT 가 그 결과를 보는지는 여기서 알 수
 * 없다 — 그건 실구동 몫이다.
 *
 * fake 를 **중첩 클래스**로 둔 이유는 [NotificationQueryServiceTest] 와 같다 — 같은 패키지의
 * `NotificationDispatcherTest` 가 top-level `RecordingNotificationRepository` 를 이미 갖고 있다.
 */
class NotificationCommandServiceTest {

    private val me = 1L
    private val other = 2L

    private val notifications = FakeNotificationStore()
    private val service = NotificationCommandService(notificationRepository = notifications)

    // ─── 1. 정상 경로 ────────────────────────────────────────────

    @Test
    fun `안 읽은 알림이 있으면 전부 읽음으로 바뀌고 0 을 반환한다`() {
        put(id = 10L)
        put(id = 11L)
        put(id = 12L)

        val remaining = service.markAllAsRead(me)

        assertEquals(0, remaining, "전부 읽었으므로 남은 안 읽은 개수는 0 이다")
        assertEquals(listOf(me), notifications.markedForUsers, "mark 는 정확히 1회 호출된다")
        assertTrue(notifications.all().all { it.isRead }, "행이 실제로 읽음으로 바뀌었다")
    }

    /**
     * 반환값이 **갱신 후** 값이라는 것은 곧 *"세는 순서가 mark 다음"* 이라는 뜻이다.
     * 순서가 뒤집혀 COUNT 가 먼저 돌면 여기서 3 이 나온다.
     */
    @Test
    fun `개수는 mark 이후에 센다 - 순서가 뒤집히면 갱신 전 값이 나온다`() {
        (1L..3L).forEach { put(id = it) }

        assertEquals(0, service.markAllAsRead(me))
    }

    @Test
    fun `mark 에는 SecurityContext 가 준 userId 가 그대로 전달된다`() {
        put(id = 10L, userId = other)
        put(id = 11L, userId = me)

        service.markAllAsRead(me)

        assertEquals(listOf(me), notifications.markedForUsers)
        assertEquals(listOf(me), notifications.countedForUsers)
    }

    /** 남의 수신함은 건드리지 않는다 — 읽음 처리도 본인 행에만 적용된다. */
    @Test
    fun `다른 사용자의 알림은 읽음으로 바뀌지 않는다`() {
        put(id = 20L, userId = other)
        put(id = 21L, userId = me)

        service.markAllAsRead(me)

        assertTrue(notifications.all().single { it.id == 20L }.isRead.not(), "남의 알림은 그대로다")
        assertTrue(notifications.all().single { it.id == 21L }.isRead, "내 알림만 읽음이 된다")
    }

    // ─── 2. 🔴 "0 하드코딩 금지" 회귀 장치 ────────────────────────

    /**
     * 🔴 **이 테스트가 `return 0` 을 막는 유일한 자동 장치다.**
     *
     * fake 의 mark 를 **아무것도 갱신하지 못하게** 만들어 두면(조건이 잘못 걸렸거나, 쿼리가
     * 조용히 실패했거나, 다른 커넥션을 봤을 때의 재현), 서비스가 `0` 을 하드코딩했다면 그래도
     * `0` 을 돌려준다. 그러면 **앱은 뱃지를 끄고**, 다음 홈 진입에서 개수가 되살아나는 것이
     * *"읽음 처리가 안 먹었다"* 가 아니라 *"알림이 새로 왔다"* 처럼 보인다. 서버 로그에는
     * 아무것도 남지 않는다.
     *
     * 같은 트랜잭션에서 실제로 다시 세면 그 즉시 3 이 나와 드러난다.
     */
    @Test
    fun `mark 가 아무것도 갱신하지 못하면 0 이 아니라 남은 개수가 나온다`() {
        (1L..3L).forEach { put(id = it) }
        notifications.markIsBroken = true

        val remaining = service.markAllAsRead(me)

        assertNotEquals(0, remaining, "0 을 하드코딩하면 갱신 실패가 응답에 드러나지 않는다")
        assertEquals(3, remaining, "갱신되지 않은 3건이 그대로 남아 있다")
        assertEquals(listOf(me), notifications.markedForUsers, "호출 자체는 있었다 — 갱신만 못 한 것이다")
    }

    // ─── 3. 멱등 ─────────────────────────────────────────────────

    /** 이미 다 읽은 상태에서 다시 불러도 정상 경로다 — 예외도, 0 이 아닌 값도 아니다. */
    @Test
    fun `이미 다 읽었으면 갱신 대상이 없고 0 을 반환한다`() {
        put(id = 30L, isRead = true)
        put(id = 31L, isRead = true)

        val remaining = service.markAllAsRead(me)

        assertEquals(0, remaining)
        assertEquals(0, notifications.lastMarkedRows, "이미 읽은 행은 다시 쓰지 않는다")
    }

    /**
     * 🔴 **멱등** — 두 번째 호출은 갱신할 행이 없어 `0 rows` 이고, 응답은 첫 번째와 **같다.**
     *
     * 갱신 row 수가 0 이 되는 것은 `AND is_read = false` 조건 덕이고, 그게 멱등성을 반환값으로
     * 눈에 보이게 한다 (`NotificationJpaRepository.markAllAsReadByUserId` KDoc).
     */
    @Test
    fun `연속 호출해도 같은 결과다 - 멱등`() {
        (1L..3L).forEach { put(id = it) }

        val first = service.markAllAsRead(me)
        val firstRows = notifications.lastMarkedRows
        val second = service.markAllAsRead(me)

        assertEquals(0, first)
        assertEquals(3, firstRows, "첫 호출은 3건을 갱신했다")
        assertEquals(first, second, "두 번째 호출도 같은 응답이다")
        assertEquals(0, notifications.lastMarkedRows, "두 번째는 갱신할 행이 없다")
    }

    /** 알림이 아예 없어도 정상이다 — 신규 가입 직후 홈 진입이 이 경로다. */
    @Test
    fun `알림이 0건이어도 0 을 반환한다`() {
        assertEquals(0, service.markAllAsRead(me))
    }

    // ─── fixtures ────────────────────────────────────────────────

    private fun put(
        id: Long,
        userId: Long = me,
        isRead: Boolean = false,
    ) = notifications.put(
        Notification(
            id = id,
            userId = userId,
            type = NotificationType.CHALLENGE_ACCEPTED,
            title = "계약 완료.",
            body = "민수님이 수락했습니다",
            referenceId = 1001L,
            isRead = isRead,
            createdAt = LocalDateTime.of(2026, 9, 1, 13, 42, 7),
        ),
    )

    // ─── in-memory fake (중첩 — 패키지 내 이름 혼동 회피) ──────────

    /**
     * 실제 구현과 **같은 조건**을 지킨다 — mark 도 count 도 `isRead = false` 인 내 행만 본다.
     * 어긋나면 이 테스트가 거짓말을 한다.
     *
     * [markIsBroken] 은 *"UPDATE 가 한 행도 건드리지 못한 상황"* 을 재현하는 스위치다 — 그 상황을
     * 만들 수 없으면 `return 0` 하드코딩을 잡을 방법이 없다.
     */
    private class FakeNotificationStore : NotificationRepository {
        private val store = LinkedHashMap<Long, Notification>()

        /** mark 가 **몇 번** 불렸는지와 **누구로** 불렸는지 둘 다 본다. */
        val markedForUsers = mutableListOf<Long>()

        val countedForUsers = mutableListOf<Long>()

        /** 직전 mark 가 실제로 갱신한 row 수 — 멱등성의 *"두 번째는 0 건"* 을 단언하는 축. */
        var lastMarkedRows: Int = -1
            private set

        /** true 면 mark 가 호출은 되지만 **아무것도 갱신하지 않는다** (0 rows). */
        var markIsBroken = false

        fun put(n: Notification) {
            store[n.id!!] = n
        }

        fun all(): List<Notification> = store.values.toList()

        override fun countUnreadByUserId(userId: Long): Long {
            countedForUsers += userId
            return store.values.count { it.userId == userId && !it.isRead }.toLong()
        }

        override fun markAllAsReadByUserId(userId: Long): Int {
            markedForUsers += userId
            if (markIsBroken) {
                lastMarkedRows = 0
                return 0
            }
            // 실제 UPDATE 와 같은 조건 — 이미 읽은 행은 대상이 아니다.
            val targets = store.values.filter { it.userId == userId && !it.isRead }
            targets.forEach { store[it.id!!] = it.copy(isRead = true) }
            lastMarkedRows = targets.size
            return targets.size
        }

        override fun save(notification: Notification): Notification =
            error("읽음 처리 서비스가 저장을 시도했다")

        override fun findPageByUserId(userId: Long, cursor: Long?, limit: Int): List<Notification> =
            error("읽음 처리 서비스가 목록을 조회했다 — 그건 NotificationQueryService 소관이다")

        override fun deleteAllByUserId(userId: Long): Int =
            error("읽음 처리 서비스가 삭제를 시도했다")
    }
}
