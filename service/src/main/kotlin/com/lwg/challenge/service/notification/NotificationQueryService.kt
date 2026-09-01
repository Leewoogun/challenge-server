package com.lwg.challenge.service.notification

import com.lwg.challenge.domain.notification.Notification
import com.lwg.challenge.domain.notification.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 알림 **읽기** 전용 서비스 — `GET /api/v1/notifications` ([getNotifications]) 와
 * `GET /api/v1/notifications/unread-count` ([getUnreadCount]). 각각 단일 read 트랜잭션이다.
 *
 * 상태를 바꾸는 읽음 처리는 여기 없다 — `NotificationCommandService` 소관이다
 * (`ChallengeCommandService` vs `ActiveChallengeService` 와 같은 분리).
 *
 * ## 🔴 COUNT 쿼리를 걸지 않는다 — `size + 1` 을 읽어 초과분으로 판정한다
 *
 * *"다음 페이지가 있나"* 를 답하는 방법은 둘이다: 전체 건수를 세거나(`COUNT`), **한 건 더 읽어
 * 보거나.** 후자를 택했다. 커서 페이지네이션은 전체 건수를 **어디에도 쓰지 않으므로**
 * (`nextCursor` 하나만 내려간다) COUNT 는 순수한 추가 쿼리다 — 알림이 쌓일수록 매 스크롤마다
 * 전체 테이블을 세는 비용이 커진다.
 *
 * 그래서 저장소에는 항상 `size + 1` 이 간다. 돌아온 건수가 `size` 를 넘으면 다음이 있는 것이고,
 * 넘은 1건은 **응답에서 잘라 버린다** ([NotificationPage.notifications] 는 정확히 `size` 이하다).
 *
 * ⚠️ **정확히 `size` 건이 있는 경계**가 이 방식의 유일한 함정이다 — `size + 1` 을 요청했는데
 * `size` 만 돌아오므로 `hasMore = false` 다. COUNT 방식이었다면 여기서 `nextCursor` 를 실어
 * 앱이 빈 페이지를 한 번 더 왕복했을 자리다.
 *
 * 정렬·커서 축이 `createdAt` 이 아니라 `id` 인 근거는 `NotificationJpaRepository` KDoc 에 있다.
 */
@Service
class NotificationQueryService(
    private val notificationRepository: NotificationRepository,
) {

    /**
     * [userId] 의 알림을 최신순으로 한 페이지 반환한다.
     *
     * @param cursor 직전 페이지의 [NotificationPage.nextCursor]. **null 이면 첫 페이지.**
     *   그대로 저장소에 넘어간다 — 이 서비스는 커서를 해석하지 않는다.
     * @param size 화면이 원하는 건수. **1 이상**을 전제한다 — 범위 검증(1..50)은 컨트롤러가
     *   이미 끝냈고, 여기서 다시 clamp 하면 검증이 두 곳에 살면서 서로 다른 답을 낼 수 있다.
     */
    @Transactional(readOnly = true)
    fun getNotifications(userId: Long, cursor: Long?, size: Int): NotificationPage {
        // 🔴 size 가 아니라 size + 1 이다 — 초과분 1건의 존재가 곧 hasMore 다.
        val rows: List<Notification> = notificationRepository.findPageByUserId(userId, cursor, size + 1)
        val hasMore: Boolean = rows.size > size
        val page: List<NotificationView> = rows.take(size).map { it.toView() }

        return NotificationPage(
            notifications = page,
            // 다음 페이지는 이 페이지의 **마지막 id 미만**부터다 (저장소가 `id < cursor` 로 자른다).
            nextCursor = if (hasMore) page.last().notificationId else null,
        )
    }

    /**
     * [userId] 의 **안 읽은** 알림 개수 — `GET /api/v1/notifications/unread-count`.
     *
     * 홈 진입 때마다 도는 값이라 목록 조회와 달리 **행을 읽지 않고 집계만** 한다. V1 의
     * `idx_notifications_user_read_created` 선두 두 컬럼이 이 WHERE 절과 맞는다
     * (`NotificationJpaRepository.countUnreadByUserId` KDoc).
     *
     * 저장소가 `Long` 을 주는 것은 JPA `COUNT` 의 타입일 뿐이고, wire 로 나가는 뱃지 숫자는
     * `Int` 다 — 한 사람의 수신함이 `Int` 를 넘길 일은 없다.
     */
    @Transactional(readOnly = true)
    fun getUnreadCount(userId: Long): Int =
        notificationRepository.countUnreadByUserId(userId).toInt()

    // ─── helpers ─────────────────────────────────────────────────

    private fun Notification.toView(): NotificationView = NotificationView(
        // 보관함(`ChallengeHistoryService`)과 같은 패턴 — 조회로 나온 row 의 id 는 null 일 수 없다.
        notificationId = id ?: error("영속화된 notification 의 id 는 null 일 수 없습니다"),
        // 🔴 enum 이 아니라 name 문자열이다 — 근거는 [NotificationView.type] KDoc.
        type = type.name,
        // 🔴 title 은 싣지 않는다 — 화면이 그리지 않는 값이다 ([NotificationView] KDoc).
        // 도메인/DB 의 title 은 그대로 살아 있다 (푸시 헤드라인이 그 값을 쓴다).
        body = body,
        referenceId = referenceId,
        createdAt = createdAt,
    )
}

/**
 * 알림 한 페이지 — 목록 **과 다음 커서**.
 *
 * `isRead` 는 이 응답 어디에도 없다 (의도적 부재 — [NotificationView] KDoc).
 */
data class NotificationPage(
    /** 최신순(`id` 내림차순). 알림이 없으면 **빈 리스트**다 — 빈 목록은 정상 응답이다. */
    val notifications: List<NotificationView>,
    /**
     * 다음 페이지 요청에 실을 커서. **다음이 없으면 null** 이고, 그 null 이 목록의 끝을 뜻한다.
     *
     * 값은 [notifications] 의 **마지막 항목 id** 다 — 다음 요청은 그 id **미만**부터 읽는다.
     */
    val nextCursor: Long?,
)

/**
 * 알림 1건의 뷰 모델.
 *
 * ## 🔴 [type] 이 `NotificationType` 이 아니라 `String` 인 것이 결정이다
 *
 * 근거는 컨트롤러 DTO 쪽과 같다 (`NotificationDto.type` KDoc) — 요약하면 **wire 바이트는 같지만
 * SpringDoc 이 내는 OpenAPI 스키마가 다르고**, 그 스펙을 본 모바일이 닫힌 enum 을 만들면 서버가
 * 타입을 추가하는 순간 목록 응답 전체의 역직렬화가 깨진다. 여기서 이미 문자열로 접어 두면
 * 컨트롤러가 다시 enum 으로 되돌릴 여지가 없다.
 *
 * ## 🔴 `isRead` 가 없는 것도 결정이다 — 읽음 처리가 붙은 지금도 그렇다
 *
 * 읽음 처리는 **행 단위가 아니라 *"목록을 열면 전부 읽음"*** 이다 ([NotificationCommandService]).
 * 목록을 여는 순간 전부 읽음이 되므로 행별 값은 여전히 정보량이 0이고, 안 읽음을 표현하는 축은
 * 목록이 아니라 **홈 벨 뱃지** 하나다 ([NotificationQueryService.getUnreadCount]). 자세한 근거는
 * `NotificationDto` KDoc. (`notifications.is_read` 컬럼 자체는 V1 부터 있다.)
 *
 * ## 🔴 `title` 이 없는 것도 결정이다 — **저장하지 않는다는 뜻이 아니다**
 *
 * ⚠️ `Notification.title` 과 `notifications.title` 컬럼은 **그대로 있다.** 발송 시점 문구가 박제돼
 * 남아 있고(`NotificationMessages` KDoc) 푸시가 그 값을 헤드라인으로 쓴다. 여기서 빠진 이유는
 * **화면이 그리지 않아서**다 — design §3.3 판정으로 알림 카드는 [body] 단독 1행이고, 실측
 * `title` 4종(`영혼의 맹세` / `계약 완료.` / `ㅠㅠ` / `증거 도착`)은 푸시 헤드라인이라 목록에서
 * 홀로 서지 못한다. 자세한 근거는 `NotificationDto` KDoc.
 *
 * 여기서 접어 두면 컨트롤러가 다시 실을 여지가 없다 — `type` 을 String 으로 접어 둔 것과 같다.
 * 화면이 2행을 원하게 되면 **필드 추가는 non-breaking** 이므로 그때 되살리면 된다.
 */
data class NotificationView(
    val notificationId: Long,
    /** `NotificationType` 의 enum name. 서버가 아는 이름이 아닐 수도 있다 — 위 KDoc 참조. */
    val type: String,
    /**
     * 발송 당시 문구가 그대로 박제된 값이다 (`NotificationMessages` KDoc). 재조립하지 않는다.
     *
     * 🔴 카드가 그리는 **유일한 문구**다 — `title` 은 여기 없다 (위 KDoc).
     */
    val body: String,
    /** 타입에 따라 참조 대상이 다르다. `CHALLENGE_*` 는 `challenges.id`. **null 일 수 있다.** */
    val referenceId: Long?,
    /** KST 벽시계 (ADR-0010). 🔴 정렬 축이 아니다 — 정렬은 `id` 다. */
    val createdAt: LocalDateTime,
)
