package com.lwg.challenge.service.notification

import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.notification.Notification
import com.lwg.challenge.domain.notification.NotificationRepository
import com.lwg.challenge.domain.notification.NotificationType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
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
 *
 * ## 🔴 `referencePending` 은 **배치 조회 1회**로 채운다 — join 이 아니다
 *
 * 목록을 그린 뒤 그 페이지의 `CHALLENGE_REQUEST` 참조 id 를 모아
 * [ChallengeRepository.findPendingReceivedIdsIn] 을 **한 번** 부른다. 그래서 **페이지 크기와
 * 무관하게 총 2쿼리**이고(목록 1 + 판정 1), COUNT 는 여전히 없다. `CHALLENGE_REQUEST` 가 한 건도
 * 없으면 두 번째 쿼리를 **아예 걸지 않는다** — `IN` 절 빈 컬렉션 가드를 겸한다
 * (`findActiveByUser`·`findDueByStatus` 의 선례와 같은 자리).
 *
 * ## 🔴 join 을 쓰지 않는 이유는 **성능이 아니라 정합성**이다
 *
 * `notifications.reference_id` 는 **FK 가 아니고, 타입마다 참조 대상이 다르다** (V8 의
 * `COMMENT ON COLUMN`). 지금 3종이 전부 `challenges.id` 인 것은 **오늘의 사실이지 규약이 아니다** —
 * `FRIEND_REQUEST` 는 `users.id` 를 가리킬 예정으로 이미 선언돼 있다 (`NotificationType`).
 *
 * 그래서 `notifications.reference_id = challenges.id` 로 조인하면, `FRIEND_REQUEST` 가 열리는
 * 순간 **`users.id` 를 `challenges.id` 에 조인한다.** 숫자가 겹치면 **엉뚱한 챌린지의 상태가 친구
 * 요청 알림에 붙고**, 안 겹치면 (INNER JOIN 일 때) **행이 통째로 사라진다.** 어느 쪽도 예외를
 * 던지지 않는다 — 조인은 성공하고 응답도 200 이다.
 *
 * 🔴 **타입 필터를 조인 조건 밖에 두면 조용히 틀린다.** 배치 조회는 타입 필터가 **id 를 모으는
 * 단계**에 있어서 애초에 challenge 가 아닌 참조는 쿼리에 들어가지도 않는다 — 조인 순서에 정합성이
 * 걸리지 않는다.
 */
@Service
class NotificationQueryService(
    private val notificationRepository: NotificationRepository,
    private val challengeRepository: ChallengeRepository,
    /** 만료 경계(`deadline > now`) 판정 기준. 주입받아야 테스트가 시각을 고정할 수 있다 (ADR-0010). */
    private val clock: Clock,
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
        // 🔴 초과분을 잘라 낸 **최종 페이지**를 기준으로 판정한다 — 응답에 나가지 않을 1건의
        //    referenceId 까지 두 번째 쿼리에 실으면 안 쓸 행을 묻는 것이다.
        val visible: List<Notification> = rows.take(size)
        val pendingReferenceIds: Set<Long> = findPendingReferenceIds(userId, visible)
        val page: List<NotificationView> = visible.map { it.toView(pendingReferenceIds) }

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

    /**
     * [page] 가 참조하는 챌린지 중 **[userId] 가 아직 응답 대기 중인 것**의 id 집합.
     *
     * 🔴 **`CHALLENGE_REQUEST` 의 `referenceId` 만** 모은다. 다른 타입의 `referenceId` 는
     * challenge 를 가리킨다는 보장이 없다 (V8 컬럼 주석 — 클래스 KDoc). 여기서 섞으면
     * `FRIEND_REQUEST` 의 `users.id` 를 challenges 테이블에 묻게 된다.
     *
     * 🔴 **비어 있으면 쿼리를 걸지 않는다** — `IN` 절 빈 컬렉션 가드이자, 알림에
     * `CHALLENGE_REQUEST` 가 없는 흔한 페이지에서 쿼리 1개를 통째로 없애는 자리다.
     */
    private fun findPendingReferenceIds(userId: Long, page: List<Notification>): Set<Long> {
        val referenceIds: Set<Long> = page
            .filter { it.type == NotificationType.CHALLENGE_REQUEST }
            .mapNotNull { it.referenceId }
            .toSet()
        if (referenceIds.isEmpty()) return emptySet()

        // 🔴 페이지당 **1회**다. 행마다 부르면 그게 N+1 이다.
        return challengeRepository.findPendingReceivedIdsIn(
            opponentId = userId,
            ids = referenceIds,
            // 만료 판정 기준 — 주입된 Clock 이 정본이다 (`LocalDateTime.now()` 를 직접 부르지 않는다).
            now = LocalDateTime.now(clock),
        )
    }

    private fun Notification.toView(pendingReferenceIds: Set<Long>): NotificationView = NotificationView(
        // 보관함(`ChallengeHistoryService`)과 같은 패턴 — 조회로 나온 row 의 id 는 null 일 수 없다.
        notificationId = id ?: error("영속화된 notification 의 id 는 null 일 수 없습니다"),
        // 🔴 enum 이 아니라 name 문자열이다 — 근거는 [NotificationView.type] KDoc.
        type = type.name,
        // 🔴 title 은 싣지 않는다 — 화면이 그리지 않는 값이다 ([NotificationView] KDoc).
        // 도메인/DB 의 title 은 그대로 살아 있다 (푸시 헤드라인이 그 값을 쓴다).
        body = body,
        referenceId = referenceId,
        // 🔴 `CHALLENGE_REQUEST` 만 non-null 이다 — 다른 타입에는 이 개념이 없다.
        //    `referenceId` 가 null 이면 물어볼 대상 자체가 없으므로 false 다 (non-null 보장 유지).
        referencePending = when (type) {
            NotificationType.CHALLENGE_REQUEST -> referenceId != null && referenceId in pendingReferenceIds
            else -> null
        },
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
    /**
     * *"이 알림이 가리키는 대상이 아직 응답을 기다리는가."*
     *
     * `true` 응답 대기 / `false` 처리·만료·종료 **또는 삭제** / `null` 이 타입엔 개념이 없다.
     *
     * 🔴 **[type] 이 `CHALLENGE_REQUEST` 면 항상 non-null** 이고, 그 외 타입은 전부 `null` 이다.
     * 산출 술어는 홈의 '받은 도전장'과 **같다** — `opponent = 수신자 AND PENDING AND deadline > now`
     * ([ChallengeRepository.findPendingReceivedIdsIn]). 자세한 의미는 `NotificationDto` KDoc.
     */
    val referencePending: Boolean?,
    /** KST 벽시계 (ADR-0010). 🔴 정렬 축이 아니다 — 정렬은 `id` 다. */
    val createdAt: LocalDateTime,
)
