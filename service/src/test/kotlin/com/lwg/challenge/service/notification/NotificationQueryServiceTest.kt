package com.lwg.challenge.service.notification

import com.lwg.challenge.domain.notification.Notification
import com.lwg.challenge.domain.notification.NotificationRepository
import com.lwg.challenge.domain.notification.NotificationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * [NotificationQueryService] 단위 테스트 — 커서 페이지네이션의 판정 규칙.
 *
 * ## 무엇을 덮는가
 *
 * 1. **`size + 1` 을 읽는다.** 이 서비스의 존재 이유가 *"COUNT 없이 hasMore 를 안다"* 이고,
 *    그 구현이 저장소에 `size` 가 아니라 `size + 1` 을 넘기는 것 하나다.
 * 2. **초과분은 잘라 낸다.** 응답 목록은 언제나 `size` 이하다.
 * 3. **`nextCursor`** — 다음이 있으면 마지막 항목의 id, 없으면 null.
 * 4. 🔴 **정확히 `size` 건인 경계.** `size + 1` 을 읽었는데 `size` 만 왔으므로 **다음이 없다.**
 *    한 칸 틀리면 여기서만 깨진다 — 다른 케이스는 전부 통과한다.
 * 5. **`cursor` 는 해석하지 않고 그대로 저장소에 넘긴다.**
 * 6. **`type` 이 enum 이 아니라 String** 으로 매핑된다 (열린 집합 결정 — `NotificationDto` KDoc).
 * 7. **`getUnreadCount`** — 저장소가 센 값을 그대로 `Int` 로 돌려주고, 읽은 행·남의 행은 세지 않는다.
 *    (상태를 **바꾸는** 쪽은 여기가 아니라 [NotificationCommandServiceTest] 다.)
 *
 * ⚠️ **저장소를 흉내낸 것은 `id DESC` 정렬 · `id < cursor` 필터 · `limit` 까지다.** 실제 JPQL 이
 * 같은 규칙으로 자르는지는 여기서 알 수 없다 — fake 가 같은 규칙을 다시 구현할 뿐이다.
 *
 * fake 를 **중첩 클래스**로 둔 이유: 같은 패키지의 `NotificationDispatcherTest` 가 top-level
 * `RecordingNotificationRepository` 를 이미 갖고 있어 나란히 두면 이름이 헷갈린다
 * (`ChallengeHistoryServiceTest` 와 같은 처리).
 */
class NotificationQueryServiceTest {

    private val me = 1L
    private val other = 2L

    private val notifications = FakeNotificationStore()
    private val service = NotificationQueryService(notificationRepository = notifications)

    // ─── 1. 건수별 동작 ──────────────────────────────────────────

    /** 알림이 하나도 없으면 빈 목록이고 커서도 없다 — 빈 목록은 **에러가 아니라 정상**이다. */
    @Test
    fun `알림이 0건이면 빈 목록이고 nextCursor 가 null 이다`() {
        val page = service.getNotifications(me, cursor = null, size = 20)

        assertTrue(page.notifications.isEmpty())
        assertNull(page.nextCursor, "더 읽을 것이 없으면 커서가 없다")
    }

    @Test
    fun `size 보다 적으면 전부 반환하고 nextCursor 가 null 이다`() {
        put(id = 10L)
        put(id = 11L)
        put(id = 12L)

        val page = service.getNotifications(me, cursor = null, size = 20)

        assertEquals(listOf(12L, 11L, 10L), page.notifications.map { it.notificationId }, "최신순(id DESC)")
        assertNull(page.nextCursor)
    }

    /**
     * 🔴 **경계 — 정확히 `size` 건.**
     *
     * `size + 1` 을 요청했는데 `size` 만 돌아왔다 ⇒ 초과분이 없다 ⇒ **다음 페이지가 없다.**
     * 여기서 `nextCursor` 가 실려 나가면 앱이 빈 페이지를 한 번 더 왕복하고, 그 왕복은
     * *"목록 끝에서 스피너가 한 번 더 돈다"* 로만 보여 버그로 신고되지 않는다.
     */
    @Test
    fun `정확히 size 만큼 있으면 전부 반환하고 nextCursor 가 null 이다`() {
        (1L..3L).forEach { put(id = it) }

        val page = service.getNotifications(me, cursor = null, size = 3)

        assertEquals(3, page.notifications.size)
        assertEquals(listOf(3L, 2L, 1L), page.notifications.map { it.notificationId })
        assertNull(page.nextCursor, "size + 1 을 읽어 초과분이 없었으므로 다음은 없다")
    }

    @Test
    fun `size 보다 많으면 size 개만 반환하고 nextCursor 가 마지막 항목의 id 다`() {
        (1L..10L).forEach { put(id = it) }

        val page = service.getNotifications(me, cursor = null, size = 3)

        assertEquals(listOf(10L, 9L, 8L), page.notifications.map { it.notificationId })
        assertEquals(
            8L,
            page.nextCursor,
            "다음 요청은 이 id 미만부터 읽는다 — 초과분 7 이 아니라 마지막으로 **반환한** 8 이다",
        )
    }

    // ─── 2. 저장소에 넘기는 인자 ─────────────────────────────────

    /**
     * 🔴 **`size` 가 아니라 `size + 1` 이다.** 이 한 줄이 COUNT 쿼리를 없앤 근거 전부다 —
     * `size` 만 읽으면 *"다음이 있나"* 를 알 방법이 사라져 COUNT 가 되살아난다.
     */
    @Test
    fun `저장소에는 limit 으로 size + 1 이 전달된다`() {
        service.getNotifications(me, cursor = null, size = 20)

        assertEquals(21, notifications.lastLimit)
    }

    @Test
    fun `size 가 1 이어도 limit 은 2 다`() {
        service.getNotifications(me, cursor = null, size = 1)

        assertEquals(2, notifications.lastLimit)
    }

    /** 커서는 이 서비스가 해석하지 않는다 — 그대로 흘려보낸다 (`id < cursor` 판정은 저장소 몫). */
    @Test
    fun `cursor 가 그대로 저장소에 전달된다`() {
        service.getNotifications(me, cursor = 38L, size = 20)

        assertEquals(38L, notifications.lastCursor)
        assertEquals(me, notifications.lastUserId)
    }

    @Test
    fun `cursor 가 null 이면 null 그대로 전달된다 - 첫 페이지`() {
        service.getNotifications(me, cursor = null, size = 20)

        assertNull(notifications.lastCursor, "첫 페이지는 커서 없이 최신부터다")
    }

    /** 커서 이후 페이지도 같은 규칙으로 이어진다 — 커서로 받은 행 자신은 다시 나오지 않는다. */
    @Test
    fun `cursor 이후 페이지는 그 id 미만부터 이어진다`() {
        (1L..10L).forEach { put(id = it) }

        val page = service.getNotifications(me, cursor = 8L, size = 3)

        assertEquals(listOf(7L, 6L, 5L), page.notifications.map { it.notificationId })
        assertEquals(5L, page.nextCursor)
    }

    // ─── 3. 뷰 매핑 ──────────────────────────────────────────────

    /**
     * 🔴 **`type` 은 enum 이 아니라 `String` 이다** (열린 집합 결정 — `NotificationDto` KDoc).
     *
     * 여기서 `NotificationType` 이 그대로 새면 컨트롤러 DTO 도 enum 이 되고, SpringDoc 이
     * `enum: [...]` 로 **닫힌 집합을 광고**한다. 그 스펙으로 만든 모바일 typed enum 은 서버가
     * 타입을 추가하는 순간 **목록 응답 전체의 역직렬화를 깨뜨린다.**
     */
    @Test
    fun `type 이 enum 이 아니라 enum name 문자열로 매핑된다`() {
        put(id = 57L, type = NotificationType.CHALLENGE_ACCEPTED)

        val view = service.getNotifications(me, cursor = null, size = 20).notifications.single()

        // 🔴 컴파일 타임 고정 — `type` 이 enum 으로 되돌아가면 이 대입이 컴파일되지 않는다.
        val type: String = view.type

        assertEquals("CHALLENGE_ACCEPTED", type)
    }

    @Test
    fun `나머지 필드가 그대로 실린다`() {
        put(
            id = 57L,
            type = NotificationType.CHALLENGE_ACCEPTED,
            title = "계약 완료.",
            body = "민수님이 수락했습니다",
            referenceId = 1001L,
            createdAt = LocalDateTime.of(2026, 9, 1, 13, 42, 7),
        )

        val view = service.getNotifications(me, cursor = null, size = 20).notifications.single()

        assertEquals(57L, view.notificationId)
        // 🔴 title 은 뷰에 없다 — 저장은 하되(위 put 이 넣는다) 화면이 그리지 않는다
        // (`NotificationView` KDoc, design §3.3: 카드는 body 단독 1행).
        assertEquals("민수님이 수락했습니다", view.body)
        assertEquals(1001L, view.referenceId)
        assertEquals(LocalDateTime.of(2026, 9, 1, 13, 42, 7), view.createdAt)
    }

    /** `referenceId` 는 nullable 이다 — 참조 대상이 없는 타입이 생길 수 있다. */
    @Test
    fun `referenceId 는 null 일 수 있다`() {
        put(id = 58L, referenceId = null)

        assertNull(service.getNotifications(me, cursor = null, size = 20).notifications.single().referenceId)
    }

    /** 남의 알림은 보이지 않는다 — 수신함은 본인만 읽는 개인 데이터다. */
    @Test
    fun `다른 사용자의 알림은 보이지 않는다`() {
        put(id = 60L, userId = other)
        put(id = 61L, userId = me)

        assertEquals(
            listOf(61L),
            service.getNotifications(me, cursor = null, size = 20).notifications.map { it.notificationId },
        )
    }

    // ─── 4. 안 읽은 개수 (홈 벨 뱃지) ────────────────────────────

    /**
     * 저장소가 준 값이 **그대로** 나간다 — clamp 도, 상한도, 보정도 없다.
     *
     * `Long` → `Int` 변환만 일어난다 (JPA `COUNT` 의 타입이 `Long` 일 뿐, 한 사람의 수신함이
     * `Int` 를 넘길 일은 없다).
     */
    @Test
    fun `getUnreadCount 는 저장소가 센 값을 그대로 Int 로 돌려준다`() {
        (1L..3L).forEach { put(id = it) }

        val count: Int = service.getUnreadCount(me)

        assertEquals(3, count)
        assertEquals(me, notifications.lastCountedUserId, "SecurityContext 가 준 userId 로 센다")
    }

    /** 안 읽은 것이 없으면 **0** 이다 — 에러가 아니라 *"뱃지를 꺼라"* 는 정상 응답이다. */
    @Test
    fun `getUnreadCount 는 안 읽은 알림이 없으면 0 이다`() {
        assertEquals(0, service.getUnreadCount(me))
    }

    /** 이미 읽은 행은 세지 않는다 — `AND is_read = false` 가 곧 뱃지 숫자의 정의다. */
    @Test
    fun `getUnreadCount 는 이미 읽은 알림을 세지 않는다`() {
        put(id = 10L, isRead = false)
        put(id = 11L, isRead = true)
        put(id = 12L, isRead = true)

        assertEquals(1, service.getUnreadCount(me))
    }

    /** 남의 안 읽은 알림이 내 뱃지에 섞이면 안 된다 — 수신함은 본인만 읽는 개인 데이터다. */
    @Test
    fun `getUnreadCount 는 다른 사용자의 알림을 세지 않는다`() {
        put(id = 20L, userId = other)
        put(id = 21L, userId = other)
        put(id = 22L, userId = me)

        assertEquals(1, service.getUnreadCount(me))
    }

    // ─── fixtures ────────────────────────────────────────────────

    private fun put(
        id: Long,
        userId: Long = me,
        type: NotificationType = NotificationType.CHALLENGE_ACCEPTED,
        title: String = "계약 완료.",
        body: String = "민수님이 수락했습니다",
        referenceId: Long? = 1001L,
        isRead: Boolean = false,
        createdAt: LocalDateTime = LocalDateTime.of(2026, 9, 1, 13, 42, 7),
    ) = notifications.put(
        Notification(
            id = id,
            userId = userId,
            type = type,
            title = title,
            body = body,
            referenceId = referenceId,
            isRead = isRead,
            createdAt = createdAt,
        ),
    )

    // ─── in-memory fake (중첩 — 패키지 내 이름 혼동 회피) ──────────

    /**
     * 실제 구현과 **같은 필터·정렬·limit** 을 지킨다 — 어긋나면 이 테스트가 거짓말을 한다.
     *
     * 정렬은 `id DESC`, 커서는 `id < cursor`, 개수는 `take(limit)` 이다.
     * [lastLimit] / [lastCursor] / [lastUserId] 로 *"서비스가 무엇을 요청했는가"* 를 단언한다.
     */
    private class FakeNotificationStore : NotificationRepository {
        private val store = LinkedHashMap<Long, Notification>()

        var lastUserId: Long? = null
            private set

        var lastCursor: Long? = null
            private set

        var lastLimit: Int? = null
            private set

        var lastCountedUserId: Long? = null
            private set

        fun put(n: Notification) {
            store[n.id!!] = n
        }

        override fun findPageByUserId(userId: Long, cursor: Long?, limit: Int): List<Notification> {
            lastUserId = userId
            lastCursor = cursor
            lastLimit = limit
            return store.values
                .filter { it.userId == userId }
                .filter { cursor == null || it.id!! < cursor }
                .sortedByDescending { it.id }
                .take(limit)
        }

        /** 실제 쿼리와 **같은 조건** — 내 행 중 `isRead = false` 인 것만 센다. */
        override fun countUnreadByUserId(userId: Long): Long {
            lastCountedUserId = userId
            return store.values.count { it.userId == userId && !it.isRead }.toLong()
        }

        override fun save(notification: Notification): Notification = error("조회 전용 서비스가 저장을 시도했다")

        /** 상태를 바꾸는 것은 `NotificationCommandService` 소관이다 — 조회 서비스가 부르면 버그다. */
        override fun markAllAsReadByUserId(userId: Long): Int = error("조회 전용 서비스가 읽음 처리를 시도했다")

        override fun deleteAllByUserId(userId: Long): Int = error("조회 전용 서비스가 삭제를 시도했다")
    }
}
