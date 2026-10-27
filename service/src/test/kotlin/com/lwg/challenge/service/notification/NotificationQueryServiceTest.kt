package com.lwg.challenge.service.notification

import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.ReceivedChallenge
import com.lwg.challenge.domain.notification.Notification
import com.lwg.challenge.domain.notification.NotificationRepository
import com.lwg.challenge.domain.notification.NotificationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [NotificationQueryService] 단위 테스트 — 커서 페이지네이션의 판정 규칙.
 *
 * ## 무엇을 덮는가
 *
 * 1. **`size + 1` 을 읽는다.** 이 서비스의 존재 이유가 *"COUNT 없이 hasMore 를 안다"* 이고,
 *    그 구현이 저장소에 `size` 가 아니라 `size + 1` 을 넘기는 것 하나다.
 * 2. **초과분은 잘라 낸다.** 응답 목록은 언제나 `size` 이하다.
 * 3. **`nextNotificationId` / `isPageEnd`** — 다음이 있으면 **초과분 1건(= 다음 페이지 첫 항목)의 id** + `false`,
 *    없으면 null + `true`. 🔴 이번 페이지 마지막 항목의 id 가 **아니다** (ADR-0014 §3).
 *    🔴 불변식 `isPageEnd == (nextNotificationId == null)` 을 끝·중간·0건 전부에서 단언한다 (ADR-0014 §2).
 * 4. 🔴 **정확히 `size` 건인 경계.** `size + 1` 을 읽었는데 `size` 만 왔으므로 **다음이 없다.**
 *    한 칸 틀리면 여기서만 깨진다 — 다른 케이스는 전부 통과한다.
 * 5. **`nextNotificationId` 는 해석하지 않고 그대로 저장소에 넘긴다.**
 * 6. **`type` 이 enum 이 아니라 String** 으로 매핑된다 (열린 집합 결정 — `NotificationDto` KDoc).
 * 7. **`getUnreadCount`** — 저장소가 센 값을 그대로 `Int` 로 돌려주고, 읽은 행·남의 행은 세지 않는다.
 *    (상태를 **바꾸는** 쪽은 여기가 아니라 [NotificationCommandServiceTest] 다.)
 * 8. 🔴 **`referencePending` 3값 산출 + 배치 조회의 호출 규약** — 아래 §5.
 *
 * ⚠️ **저장소를 흉내낸 것은 `id DESC` 정렬 · `id <= nextNotificationId`(포함 경계) 필터 · `limit` 까지다.** 실제 JPQL 이
 * 같은 규칙으로 자르는지는 여기서 알 수 없다 — fake 가 같은 규칙을 다시 구현할 뿐이다.
 *
 * fake 를 **중첩 클래스**로 둔 이유: 같은 패키지의 `NotificationDispatcherTest` 가 top-level
 * `RecordingNotificationRepository` 를 이미 갖고 있어 나란히 두면 이름이 헷갈린다
 * (`ChallengeHistoryServiceTest` 와 같은 처리).
 */
class NotificationQueryServiceTest {

    private val me = 1L
    private val other = 2L

    /** 서버 "지금" = 2026-09-01 13:00 KST. 만료 경계가 이 시각을 기준으로 갈린다. */
    private val now = LocalDateTime.of(2026, 9, 1, 13, 0)

    private val notifications = FakeNotificationStore()
    private val challenges = RecordingChallengeStore()
    private val service = NotificationQueryService(
        notificationRepository = notifications,
        challengeRepository = challenges,
        // 🔴 고정 시계 — `deadline > now` 경계를 결정적으로 재현하기 위해서다 (ADR-0010).
        clock = Clock.fixed(now.atZone(KstTime.ZONE).toInstant(), KstTime.ZONE),
    )

    // ─── 1. 건수별 동작 ──────────────────────────────────────────

    /** 알림이 하나도 없으면 빈 목록이고 끝이다 — 빈 목록은 **에러가 아니라 정상**이다. */
    @Test
    fun `알림이 0건이면 빈 목록이고 isPageEnd 가 true 이고 nextNotificationId 가 null 이다`() {
        val page = service.getNotifications(me, nextNotificationId = null, size = 20)

        assertTrue(page.notifications.isEmpty())
        assertNull(page.nextNotificationId, "더 읽을 것이 없으면 다음 요청 값이 없다")
        assertTrue(page.isPageEnd, "0건도 끝이다")
    }

    @Test
    fun `size 보다 적으면 전부 반환하고 끝 페이지다`() {
        put(id = 10L)
        put(id = 11L)
        put(id = 12L)

        val page = service.getNotifications(me, nextNotificationId = null, size = 20)

        assertEquals(listOf(12L, 11L, 10L), page.notifications.map { it.notificationId }, "최신순(id DESC)")
        assertNull(page.nextNotificationId)
        assertTrue(page.isPageEnd)
    }

    /**
     * 🔴 **경계 — 정확히 `size` 건.**
     *
     * `size + 1` 을 요청했는데 `size` 만 돌아왔다 ⇒ 초과분이 없다 ⇒ **다음 페이지가 없다.**
     * 여기서 `nextNotificationId` 가 실려 나가고 `isPageEnd = false` 가 되면 앱이 빈 페이지를 한 번 더 왕복하고, 그 왕복은
     * *"목록 끝에서 스피너가 한 번 더 돈다"* 로만 보여 버그로 신고되지 않는다.
     */
    @Test
    fun `정확히 size 만큼 있으면 전부 반환하고 끝 페이지다`() {
        (1L..3L).forEach { put(id = it) }

        val page = service.getNotifications(me, nextNotificationId = null, size = 3)

        assertEquals(3, page.notifications.size)
        assertEquals(listOf(3L, 2L, 1L), page.notifications.map { it.notificationId })
        assertNull(page.nextNotificationId, "size + 1 을 읽어 초과분이 없었으므로 다음은 없다")
        assertTrue(page.isPageEnd, "size + 1 을 읽어 초과분이 없었으므로 끝이다")
    }

    @Test
    fun `size 보다 많으면 size 개만 반환하고 nextNotificationId 가 초과분의 id 다`() {
        (1L..10L).forEach { put(id = it) }

        val page = service.getNotifications(me, nextNotificationId = null, size = 3)

        assertEquals(listOf(10L, 9L, 8L), page.notifications.map { it.notificationId })
        assertEquals(
            7L,
            page.nextNotificationId,
            "다음 페이지 첫 항목 = 잘라 낸 초과분 7 이다 — 마지막으로 **반환한** 8 이 아니다 (ADR-0014 §3)",
        )
        assertFalse(page.isPageEnd, "초과분이 있었으므로 끝이 아니다")
    }

    // ─── 2. 저장소에 넘기는 인자 ─────────────────────────────────

    /**
     * 🔴 **`size` 가 아니라 `size + 1` 이다.** 이 한 줄이 COUNT 쿼리를 없앤 근거 전부다 —
     * `size` 만 읽으면 *"다음이 있나"* 를 알 방법이 사라져 COUNT 가 되살아난다.
     */
    @Test
    fun `저장소에는 limit 으로 size + 1 이 전달된다`() {
        service.getNotifications(me, nextNotificationId = null, size = 20)

        assertEquals(21, notifications.lastLimit)
    }

    @Test
    fun `size 가 1 이어도 limit 은 2 다`() {
        service.getNotifications(me, nextNotificationId = null, size = 1)

        assertEquals(2, notifications.lastLimit)
    }

    /** `nextNotificationId` 는 이 서비스가 해석하지 않는다 — 그대로 흘려보낸다 (`id <=` 판정은 저장소 몫). */
    @Test
    fun `nextNotificationId 가 그대로 저장소에 전달된다`() {
        service.getNotifications(me, nextNotificationId = 38L, size = 20)

        assertEquals(38L, notifications.requestedNextNotificationId)
        assertEquals(me, notifications.lastUserId)
    }

    @Test
    fun `nextNotificationId 가 null 이면 null 그대로 전달된다 - 첫 페이지`() {
        service.getNotifications(me, nextNotificationId = null, size = 20)

        assertNull(notifications.requestedNextNotificationId, "첫 페이지는 최신부터다")
    }

    /** 🔴 **포함 경계** — `nextNotificationId` 행 자신이 다음 페이지의 **첫 행**이다. */
    @Test
    fun `nextNotificationId 를 보내면 그 id 를 포함해 이하부터 이어진다`() {
        (1L..10L).forEach { put(id = it) }

        val page = service.getNotifications(me, nextNotificationId = 8L, size = 3)

        assertEquals(listOf(8L, 7L, 6L), page.notifications.map { it.notificationId })
        assertEquals(5L, page.nextNotificationId)
        assertFalse(page.isPageEnd)
    }

    /**
     * 🔴 **불변식 `isPageEnd == (nextNotificationId == null)` — 끝까지 걸으며 매 페이지 단언한다.**
     *
     * ADR-0014 가 끝 신호를 둘로 늘렸고 이 단언이 **유일한 방어**다. 앱이 하는 그대로 — 받은
     * `nextNotificationId` 를 되돌려 보내고 `isPageEnd` 에서 멈춘다 — 걸어서 중간 페이지(false) 와
     * 끝 페이지(true) 를 한 번에 지나간다. 중복 0 / 누락 0 도 함께 본다 — 🔴 포함 경계라 응답 값을
     * 마지막 항목 id 로 잘못 내면 **경계 항목이 두 번** 나오고, 그게 여기서 깨진다.
     */
    @Test
    fun `끝까지 걸으면 매 페이지에서 isPageEnd 와 nextNotificationId 가 어긋나지 않는다`() {
        (1L..7L).forEach { put(id = it) }

        val pages = mutableListOf<NotificationPage>()
        var next: Long? = null
        do {
            val page = service.getNotifications(me, nextNotificationId = next, size = 3)
            pages += page
            next = page.nextNotificationId
        } while (!page.isPageEnd && pages.size < 10)

        assertEquals(listOf(false, false, true), pages.map { it.isPageEnd }, "3 + 3 + 1 — 마지막만 끝이다")
        assertEquals(listOf(4L, 1L, null), pages.map { it.nextNotificationId }, "다음 페이지 첫 항목의 id")
        pages.forEach { page ->
            assertEquals(page.nextNotificationId == null, page.isPageEnd, "끝 신호 두 개가 어긋났다: $page")
        }
        assertEquals(
            (7L downTo 1L).toList(),
            pages.flatMap { it.notifications }.map { it.notificationId },
            "중복 0 / 누락 0",
        )
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

        val view = service.getNotifications(me, nextNotificationId = null, size = 20).notifications.single()

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

        val view = service.getNotifications(me, nextNotificationId = null, size = 20).notifications.single()

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

        assertNull(service.getNotifications(me, nextNotificationId = null, size = 20).notifications.single().referenceId)
    }

    /** 남의 알림은 보이지 않는다 — 수신함은 본인만 읽는 개인 데이터다. */
    @Test
    fun `다른 사용자의 알림은 보이지 않는다`() {
        put(id = 60L, userId = other)
        put(id = 61L, userId = me)

        assertEquals(
            listOf(61L),
            service.getNotifications(me, nextNotificationId = null, size = 20).notifications.map { it.notificationId },
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

    // ─── 5. referencePending (신청 알림의 대상이 아직 살아 있는가) ──

    /**
     * 🔴 **대상이 응답 대기 중이면 `true`.**
     *
     * 술어는 홈 '받은 도전장'과 같다 — `opponent = 수신자 AND PENDING AND deadline > now`.
     * 여기가 `true` 인 알림을 탭하면 홈에 그 카드가 **반드시 있다.**
     */
    @Test
    fun `CHALLENGE_REQUEST 는 대상이 응답 대기 중이면 referencePending 이 true 다`() {
        challenges.put(challenge(id = 1001L, opponentId = me, deadline = now.plusHours(5)))
        put(id = 57L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 1001L)

        val view = service.getNotifications(me, nextNotificationId = null, size = 20).notifications.single()

        assertEquals(true, view.referencePending)
    }

    /** 처리됐거나(수락/거절) 삭제된 챌린지는 집합에 없다 — 별도 분기 없이 `false` 다. */
    @Test
    fun `CHALLENGE_REQUEST 는 대상이 집합에 없으면 referencePending 이 false 다`() {
        // 이미 수락돼 IN_PROGRESS 가 된 챌린지 — PENDING 이 아니므로 안 걸린다.
        challenges.put(
            challenge(id = 1001L, opponentId = me, deadline = now.plusHours(5), status = ChallengeStatus.IN_PROGRESS),
        )
        put(id = 57L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 1001L)
        // 챌린저가 취소해 row 가 물리 삭제된 경우 — 저장소에 아예 없다.
        put(id = 58L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 2002L)

        val views = service.getNotifications(me, nextNotificationId = null, size = 20).notifications

        assertEquals(false, views.first { it.notificationId == 57L }.referencePending, "처리됨")
        assertEquals(false, views.first { it.notificationId == 58L }.referencePending, "삭제됨")
    }

    /**
     * 🔴 **lazy expiry — `PENDING` 인데 이미 만료된 row 는 `false` 다.**
     *
     * 마감이 지나도 DB 의 status 는 `PENDING` 그대로다 (`EXPIRED` 전이는 배치 몫). `status` 만
     * 보고 `true` 를 내리면 앱이 탭을 허용하고, 사용자는 **홈의 받은 도전장이 비어 있는 화면**에
     * 도착한다 — 이 필드가 애초에 막으려던 그 증상이다. 경계는 `>` 라 **마감 정각은 아직 유효**하다
     * (`findReceivedPending` 과 같은 규칙).
     */
    @Test
    fun `CHALLENGE_REQUEST 는 PENDING 이어도 마감이 지났으면 false 다`() {
        challenges.put(challenge(id = 1001L, opponentId = me, deadline = now.minusSeconds(1)))
        challenges.put(challenge(id = 1002L, opponentId = me, deadline = now))
        challenges.put(challenge(id = 1003L, opponentId = me, deadline = now.plusSeconds(1)))
        put(id = 57L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 1001L)
        put(id = 58L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 1002L)
        put(id = 59L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 1003L)

        val views = service.getNotifications(me, nextNotificationId = null, size = 20).notifications

        assertEquals(false, views.first { it.notificationId == 57L }.referencePending, "이미 지났다")
        assertEquals(false, views.first { it.notificationId == 58L }.referencePending, "정각은 이미 만료다 (> 경계)")
        assertEquals(true, views.first { it.notificationId == 59L }.referencePending, "1초 남았으면 살아 있다")
    }

    /**
     * 🔴 **`referenceId` 가 null 이어도 `CHALLENGE_REQUEST` 는 non-null 이다** — `false` 다.
     *
     * 물어볼 대상 자체가 없으니 *"대기 중"* 일 수 없다. 여기서 `null` 을 내리면 **"`CHALLENGE_REQUEST`
     * 면 non-null" 계약이 깨지고**, 앱은 이 타입에서 fallback 을 갖고 있지 않다.
     */
    @Test
    fun `CHALLENGE_REQUEST 는 referenceId 가 null 이면 referencePending 이 false 다`() {
        put(id = 57L, type = NotificationType.CHALLENGE_REQUEST, referenceId = null)

        val view = service.getNotifications(me, nextNotificationId = null, size = 20).notifications.single()

        assertEquals(false, view.referencePending, "null 이 아니라 false 다 — non-null 보장이 계약이다")
        assertEquals(0, challenges.callCount, "물어볼 id 가 없으면 쿼리도 없다")
    }

    /**
     * 🔴 **`CHALLENGE_REQUEST` 외 타입은 전부 `null`** — 이 타입엔 그 개념이 없다.
     *
     * `null` 은 «비탭» 이 아니라 **«이 필드를 보지 마라»** 다 (`NotificationDto` KDoc). 여기서
     * `false` 를 내리면 앱이 `false` 를 비탭으로 읽어 **증거 도착이 안 눌린다.**
     */
    @Test
    fun `CHALLENGE_REQUEST 가 아닌 타입은 referencePending 이 null 이다`() {
        // referenceId 가 challenges 에 실제로 존재해도 타입이 다르면 null 이어야 한다.
        challenges.put(challenge(id = 1001L, opponentId = me, deadline = now.plusHours(5)))
        put(id = 57L, type = NotificationType.OPPONENT_VERIFIED, referenceId = 1001L)
        put(id = 58L, type = NotificationType.CHALLENGE_ACCEPTED, referenceId = 1001L)
        put(id = 59L, type = NotificationType.CHALLENGE_REJECTED, referenceId = 1001L)

        val views = service.getNotifications(me, nextNotificationId = null, size = 20).notifications

        assertEquals(3, views.size)
        views.forEach { assertNull(it.referencePending, "type=${it.type} 에는 이 개념이 없다") }
    }

    /**
     * 🔴 **`CHALLENGE_REQUEST` 가 0건이면 배치 조회를 아예 걸지 않는다** — `IN` 절 빈 컬렉션 가드.
     *
     * 저장소 선례(`findActiveByUser`·`findDueByStatus`)와 같은 자리다. 신청 알림이 없는 페이지는
     * 흔하므로 이 가드가 곧 **쿼리 1개를 통째로 없애는 것**이기도 하다.
     */
    @Test
    fun `CHALLENGE_REQUEST 가 없으면 배치 조회를 호출하지 않는다`() {
        put(id = 57L, type = NotificationType.CHALLENGE_ACCEPTED, referenceId = 1001L)
        put(id = 58L, type = NotificationType.OPPONENT_VERIFIED, referenceId = 1002L)

        service.getNotifications(me, nextNotificationId = null, size = 20)

        assertEquals(0, challenges.callCount, "빈 IN 절을 던지지 않는다")
    }

    /**
     * 🔴 **N+1 회귀 장치 — 배치 조회는 페이지당 정확히 1회다.**
     *
     * 행마다 `findById` 를 부르는 구현으로 되돌아가면 여기서 즉시 깨진다. 목록 쿼리까지 합쳐
     * **총 2쿼리**이고 페이지 크기와 무관하다.
     */
    @Test
    fun `배치 조회는 CHALLENGE_REQUEST 가 여러 건이어도 1회만 호출된다`() {
        (1001L..1003L).forEach { challenges.put(challenge(id = it, opponentId = me, deadline = now.plusHours(5))) }
        put(id = 57L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 1001L)
        put(id = 58L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 1002L)
        put(id = 59L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 1003L)

        val views = service.getNotifications(me, nextNotificationId = null, size = 20).notifications

        assertEquals(1, challenges.callCount, "행마다 부르면 그게 N+1 이다")
        assertTrue(views.all { it.referencePending == true })
    }

    /**
     * 🔴 **다른 타입의 `referenceId` 가 섞이면 안 된다.**
     *
     * `notifications.reference_id` 는 FK 가 아니고 **타입마다 참조 대상이 다르다** (V8 컬럼 주석).
     * `FRIEND_REQUEST` 의 `users.id` 가 섞여 들어가면 challenges 테이블에 사용자 id 를 묻게 되고,
     * 숫자가 겹치는 순간 **엉뚱한 챌린지 상태가 친구 요청 알림에 붙는다.**
     */
    @Test
    fun `배치 조회에는 CHALLENGE_REQUEST 의 referenceId 만 넘어간다`() {
        put(id = 57L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 1001L)
        put(id = 58L, type = NotificationType.CHALLENGE_ACCEPTED, referenceId = 2002L)
        put(id = 59L, type = NotificationType.FRIEND_REQUEST, referenceId = 3003L)
        put(id = 60L, type = NotificationType.CHALLENGE_REQUEST, referenceId = null)

        service.getNotifications(me, nextNotificationId = null, size = 20)

        assertEquals(setOf(1001L), challenges.lastIds, "다른 타입의 참조 대상은 challenges 가 아니다")
        assertEquals(me, challenges.lastOpponentId, "판정 주체는 알림 수신자다")
        assertEquals(now, challenges.lastNow, "만료 기준은 주입된 Clock 이다")
    }

    /**
     * 🔴 **`size + 1` 로 읽은 뒤 잘라낸 페이지의 id 만 넘어간다.**
     *
     * 초과분 1건은 응답에 나가지 않으므로 그 `referenceId` 까지 두 번째 쿼리에 실으면 **쓰지도 않을
     * 행을 묻는 것**이다. `rows` 가 아니라 `rows.take(size)` 를 기준으로 모아야 한다.
     */
    @Test
    fun `배치 조회에는 잘라낸 페이지의 id 만 넘어간다`() {
        put(id = 57L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 1001L)
        put(id = 56L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 1002L)
        // id DESC 라 이 행이 3번째 = size 2 의 초과분이다.
        put(id = 55L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 1003L)

        val page = service.getNotifications(me, nextNotificationId = null, size = 2)

        assertEquals(listOf(57L, 56L), page.notifications.map { it.notificationId })
        assertEquals(setOf(1001L, 1002L), challenges.lastIds, "초과분 1003 이 섞이면 안 읽을 행을 묻는 것이다")
    }

    /** 판정 주체는 **알림 수신자**다 — 남이 받은 도전장을 내 알림에서 «대기 중» 으로 볼 수 없다. */
    @Test
    fun `상대가 opponent 인 PENDING 은 내 알림에서 false 다`() {
        challenges.put(challenge(id = 1001L, opponentId = other, deadline = now.plusHours(5)))
        put(id = 57L, type = NotificationType.CHALLENGE_REQUEST, referenceId = 1001L)

        val view = service.getNotifications(me, nextNotificationId = null, size = 20).notifications.single()

        assertFalse(view.referencePending!!, "내가 opponent 가 아니면 내가 응답할 대상이 아니다")
    }

    // ─── fixtures ────────────────────────────────────────────────

    private fun challenge(
        id: Long,
        opponentId: Long,
        deadline: LocalDateTime,
        status: ChallengeStatus = ChallengeStatus.PENDING,
    ) = Challenge(
        id = id,
        challengerId = 99L,
        opponentId = opponentId,
        challengerMission = "미션",
        opponentMission = null,
        betContent = "내기",
        challengeDate = LocalDate.of(2026, 9, 1),
        deadline = deadline,
        status = status,
        result = null,
        createdAt = LocalDateTime.of(2026, 9, 1, 9, 0),
        completedAt = null,
        forfeitedBy = null,
    )

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
     * 정렬은 `id DESC`, 다음 페이지는 `id <= nextNotificationId`(포함 경계 — JPQL 과 같다), 개수는 `take(limit)` 이다.
     * [lastLimit] / [requestedNextNotificationId] / [lastUserId] 로 *"서비스가 무엇을 요청했는가"* 를 단언한다.
     */
    private class FakeNotificationStore : NotificationRepository {
        private val store = LinkedHashMap<Long, Notification>()

        var lastUserId: Long? = null
            private set

        var requestedNextNotificationId: Long? = null
            private set

        var lastLimit: Int? = null
            private set

        var lastCountedUserId: Long? = null
            private set

        fun put(n: Notification) {
            store[n.id!!] = n
        }

        override fun findPageByUserId(userId: Long, nextNotificationId: Long?, limit: Int): List<Notification> {
            lastUserId = userId
            requestedNextNotificationId = nextNotificationId
            lastLimit = limit
            return store.values
                .filter { it.userId == userId }
                .filter { nextNotificationId == null || it.id!! <= nextNotificationId }
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

    /**
     * `referencePending` 산출용 [ChallengeRepository] fake.
     *
     * ⚠️ [findPendingReceivedIdsIn] 은 **실제 쿼리와 같은 술어**를 다시 구현한다 —
     * `opponent = :me AND PENDING AND deadline > :now`. 어긋나면 이 테스트가 거짓말을 한다
     * (`findReceivedPending` 과 조건이 같아야 한다는 것이 계약 자체다).
     *
     * [callCount] 가 **N+1 회귀 장치**이고 [lastIds] 가 *"어떤 id 를 물었는가"* 의 단언 지점이다.
     * 나머지 메서드는 이 경로에서 불리면 그 자체가 버그라 `error(...)` 다.
     */
    private class RecordingChallengeStore : ChallengeRepository {
        private val store = LinkedHashMap<Long, Challenge>()

        /** 🔴 페이지당 1회여야 한다. 2 이상이면 행마다 부르는 구현으로 되돌아간 것이다. */
        var callCount = 0
            private set

        var lastOpponentId: Long? = null
            private set

        var lastIds: Set<Long>? = null
            private set

        var lastNow: LocalDateTime? = null
            private set

        fun put(c: Challenge) {
            store[c.id!!] = c
        }

        override fun findPendingReceivedIdsIn(
            opponentId: Long,
            ids: Collection<Long>,
            now: LocalDateTime,
        ): Set<Long> {
            callCount++
            lastOpponentId = opponentId
            lastIds = ids.toSet()
            lastNow = now
            // 실제 JPQL 과 같은 조건 3개. 저장소에 없는 id(= 삭제됨)는 자연히 빠진다.
            return store.values
                .filter {
                    it.opponentId == opponentId &&
                        it.id in ids &&
                        it.status == ChallengeStatus.PENDING &&
                        it.deadline > now
                }
                .mapNotNull { it.id }
                .toSet()
        }

        override fun findActiveByUser(userId: Long, statuses: Collection<ChallengeStatus>): List<Challenge> =
            error("알림 조회가 홈 목록을 읽었다")

        override fun findRecentlyCompletedByUser(userId: Long, since: LocalDateTime): List<Challenge> =
            error("알림 조회가 최근 판정 목록을 읽었다")

        override fun findCompletedHistoryByUserInMonth(
            userId: Long,
            monthStart: LocalDate,
            nextMonthStart: LocalDate,
        ): List<Challenge> = error("알림 조회가 보관함을 읽었다")

        override fun findEarliestCompletedChallengeDate(userId: Long): LocalDate? =
            error("알림 조회가 보관함 하한을 읽었다")

        /** 🔴 행마다 단건 조회로 되돌아가는 구현을 막는다 — 그게 정확히 N+1 이다. */
        override fun findById(id: Long): Challenge? = error("배치 조회 대신 단건 조회를 썼다 (N+1)")

        /** 알림 조회는 기권 경로가 아니다 — 부르면 그게 버그다. */
        override fun findByIdForUpdate(id: Long): Challenge? = error("알림 조회가 기권용 잠금 조회를 썼다")

        override fun findReceivedPending(opponentId: Long, now: LocalDateTime): List<ReceivedChallenge> =
            error("알림 조회가 받은 도전장 목록을 읽었다 — 필요한 것은 id 집합뿐이다")

        override fun findDueByStatus(statuses: Collection<ChallengeStatus>, now: LocalDateTime): List<Challenge> =
            error("알림 조회가 배치 판정 대상을 읽었다")

        override fun findConflicting(
            userA: Long,
            userB: Long,
            challengeDate: LocalDate,
            statuses: Collection<ChallengeStatus>,
        ): List<Challenge> = error("알림 조회가 중복 판정을 했다")

        override fun save(challenge: Challenge): Challenge = error("조회 전용 서비스가 저장을 시도했다")

        override fun saveAndFlush(challenge: Challenge): Challenge = error("조회 전용 서비스가 저장을 시도했다")

        override fun deleteById(id: Long) = error("조회 전용 서비스가 삭제를 시도했다")

        override fun deletePendingSentBy(userId: Long): Int = error("조회 전용 서비스가 삭제를 시도했다")
    }
}
