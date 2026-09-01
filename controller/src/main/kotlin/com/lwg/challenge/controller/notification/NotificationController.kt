package com.lwg.challenge.controller.notification

import com.lwg.challenge.controller.notification.dto.NotificationDto
import com.lwg.challenge.controller.notification.dto.NotificationListData
import com.lwg.challenge.controller.notification.dto.NotificationListResponse
import com.lwg.challenge.controller.notification.dto.UnreadCountData
import com.lwg.challenge.controller.notification.dto.UnreadCountResponse
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.service.notification.NotificationCommandService
import com.lwg.challenge.service.notification.NotificationPage
import com.lwg.challenge.service.notification.NotificationQueryService
import com.lwg.challenge.service.notification.NotificationView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 알림 컨트롤러 — 엔드포인트 3개.
 *
 * | 경로 | 하는 일 |
 * |---|---|
 * | `GET /api/v1/notifications` | 알림 목록 (최신순 커서 페이지네이션) |
 * | `GET /api/v1/notifications/unread-count` | 홈 벨 뱃지용 안 읽은 개수 |
 * | `POST /api/v1/notifications/read-all` | *"목록을 열면 전부 읽음"* 실행 + 처리 후 개수 |
 *
 * 목록의 정렬 축이 `created_at` 이 아니라 `id` 인 근거는 `NotificationJpaRepository` KDoc 에 있다.
 *
 * ## 🔴 경로 충돌 — `{id}` 형태의 변수 경로를 새로 만들지 마라
 *
 * `/notifications/unread-count` 와 `/notifications/read-all` 은 **리터럴 세그먼트**라 목록
 * 매핑(`/notifications`)과 충돌하지 않는다. 그런데 여기에 `@GetMapping("/notifications/{id}")`
 * 같은 변수 경로가 추가되면 `GET /notifications/unread-count` 가 **`id = "unread-count"` 로도
 * 매칭 가능**해진다. Spring 은 리터럴을 우선하므로 당장 깨지지는 않지만, 그때부터 뱃지
 * 엔드포인트의 동작이 **매핑 우선순위 규칙에 의존**하게 된다.
 *
 * ## 🔴 읽음 처리는 행 단위가 아니라 *"목록을 열면 전부 읽음"* 이다
 *
 * 알림마다 읽음을 내리는 방식은 *"언제 읽음인가"* 라는 제품 결정을 계속 끌고 다닌다. 목록을 여는
 * 순간 전부 읽음으로 고정해 그 결정을 없앴고, 그래서 **엔드포인트 2개로 홈 벨 뱃지가 성립**한다.
 * `NotificationDto` 에 `isRead` 가 없는 것은 그 결정의 결과다 — 되살리지 마라.
 *
 * 인증은 SecurityFilterChain `.anyRequest().authenticated()` + `JwtAuthenticationFilter` 가
 * 담당한다 — 🔴 **`SecurityConfig` 에 이 경로를 추가할 필요가 없다.** `anyRequest()` 가 이미
 * 덮으므로 오히려 명시하면 규칙이 두 곳에 생긴다. 정상 케이스에서 SecurityContext 의 principal 은
 * userId(Long).
 *
 * ## 🔴 쿼리 파라미터를 `Long?`/`Int?` 로 선언하지 않았다 — `String?` 로 받아 직접 파싱한다
 *
 * 타입 바인딩(`cursor: Long?`)에 실패하면 Spring 이 `MethodArgumentTypeMismatchException` 을
 * 던지는데, `GlobalExceptionHandler` 에 **그 타입의 핸들러가 없어서** `handleUncaught` 로 떨어져
 * **HTTP 500 + code=500** 이 나간다. 클라이언트가 `?cursor=abc` 를 한 번 잘못 보낸 것이 **서버
 * 장애로 보고되는 것**이다 (ADR-0002: 5xx 는 인프라 장애 전용).
 *
 * 전역 핸들러를 새로 추가하는 선택지는 **다른 모든 엔드포인트의 타입 불일치 응답까지 바꾸므로**
 * 이 범위가 아니다. [ChallengeHistoryController.parseMonth][com.lwg.challenge.controller.challenge.ChallengeHistoryController]
 * 가 `?month=` 에서 정확히 같은 판단을 내린 **선례**이고, 여기도 같은 형태를 따른다 —
 * 문자열로 받아 [parseCursor] / [parseSize] 에서 파싱하고 [SnackbarException] (HTTP 200 + code=700)
 * 으로 던진다.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Notification", description = "알림 조회 / 읽음 처리")
class NotificationController(
    private val notificationQueryService: NotificationQueryService,
    private val notificationCommandService: NotificationCommandService,
) {

    @GetMapping("/notifications")
    @Operation(
        summary = "알림 목록 조회",
        description = "현재 사용자의 알림을 **최신순(id 내림차순)** 커서 페이지네이션으로 반환한다. " +
            "`cursor` 를 생략하면 첫 페이지이고, 응답의 `nextCursor` 를 다음 요청에 그대로 실으면 된다. " +
            "**`nextCursor` 가 null 이면 목록의 끝**이다 (null 이어도 키는 실려 나간다). " +
            "🔴 `size` 는 1~50 이며 **범위를 벗어나면 조용히 깎지 않고 거부한다** (HTTP 200 + code=700). " +
            "생략하면 20. " +
            "정렬 축이 `created_at` 이 아니라 `id` 인 것은 시계 역행 시 행이 조용히 건너뛰어지는 것을 " +
            "막기 위한 결정이다. " +
            "`type` 은 **열린 집합**이라 앱이 모르는 값이 올 수 있다 — typed enum 으로 만들지 마라. " +
            "알림이 없으면 `notifications: []` 로 정상 응답한다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun getNotifications(
        @Parameter(
            description = "직전 응답의 `nextCursor`. 생략하면 첫 페이지. " +
                "숫자가 아니면 HTTP 200 + code=700.",
            example = "38",
        )
        @RequestParam(name = "cursor", required = false) cursor: String?,
        @Parameter(
            // 어노테이션 인자는 컴파일 타임 상수만 허용되므로 DEFAULT_SIZE 를 문자열 템플릿으로
            // 끼워 넣지 않는다. 값이 바뀌면 이 문구도 함께 고쳐야 한다.
            description = "한 번에 받을 개수 (1~50). 생략하면 20. " +
                "범위 밖이거나 숫자가 아니면 HTTP 200 + code=700 (clamp 하지 않는다).",
            example = "20",
        )
        @RequestParam(name = "size", required = false) size: String?,
    ): NotificationListResponse {
        val userId: Long = currentUserId()
        val page: NotificationPage = notificationQueryService.getNotifications(
            userId = userId,
            cursor = parseCursor(cursor),
            size = parseSize(size),
        )
        return NotificationListResponse(
            data = NotificationListData(
                notifications = page.notifications.map { it.toDto() },
                nextCursor = page.nextCursor,
            ),
        )
    }

    /**
     * 🔴 리터럴 경로다 — `/notifications/{id}` 같은 변수 경로를 만들면 이 경로가 그쪽으로도
     * 매칭 가능해진다 (클래스 KDoc).
     */
    @GetMapping("/notifications/unread-count")
    @Operation(
        summary = "안 읽은 알림 개수 (홈 벨 뱃지)",
        description = "현재 사용자의 **안 읽은 알림 개수**를 반환한다. 홈 진입 때마다 부르는 값이라 " +
            "알림 행을 읽지 않고 집계만 한다. " +
            "🔴 앱은 뱃지를 **오직 이 `unreadCount` 값 하나로만** 켠다 — `read-all` 응답도 같은 DTO 를 " +
            "쓰므로 뱃지 갱신 경로가 하나다. " +
            "안 읽은 것이 없으면 `unreadCount: 0` 으로 정상 응답한다 (에러가 아니다).",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun getUnreadCount(): UnreadCountResponse {
        val userId: Long = currentUserId()
        return UnreadCountResponse(
            data = UnreadCountData(unreadCount = notificationQueryService.getUnreadCount(userId)),
        )
    }

    /**
     * 🔴 요청 body 가 없다 — 대상은 언제나 *"내 안 읽은 알림 전부"* 다. 범위를 받기 시작하면
     * *"언제 읽음인가"* 라는 제품 결정이 되돌아온다 (클래스 KDoc).
     */
    @PostMapping("/notifications/read-all")
    @Operation(
        summary = "알림 전부 읽음 처리",
        description = "현재 사용자의 안 읽은 알림을 **전부** 읽음으로 바꾸고, **처리 후의 안 읽은 개수**를 " +
            "반환한다 (정상 경로에서는 `0`). 요청 body 는 없다. " +
            "🔴 이 엔드포인트가 **\"목록을 열면 전부 읽음\" 정책의 실행 지점**이다 — 앱은 알림 목록 화면에 " +
            "진입할 때 이걸 부른다. 행 단위 읽음 표시는 없다. " +
            "🔴 응답이 `GET /notifications/unread-count` 와 **같은 DTO** 인 것은 의도다. 앱이 " +
            "*\"read-all 을 불렀으니 뱃지는 0\"* 이라는 규칙을 갖지 않고 **항상 서버가 준 `unreadCount` 만 " +
            "보고** 뱃지를 켜게 하기 위해서다. " +
            "**멱등하다** — 이미 다 읽은 상태에서 다시 불러도 200 + `unreadCount: 0` 이다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun readAll(): UnreadCountResponse {
        val userId: Long = currentUserId()
        return UnreadCountResponse(
            // 🔴 0 을 만들어 넣지 않는다 — 서비스가 갱신 후 실제로 센 값이다
            // (`NotificationCommandService.markAllAsRead` KDoc).
            data = UnreadCountData(unreadCount = notificationCommandService.markAllAsRead(userId)),
        )
    }

    // ─── helpers ─────────────────────────────────────────────────

    /**
     * `?cursor=38` → `38L`. 값이 없거나 빈 문자열이면 **null**(= 첫 페이지)을 그대로 넘긴다 —
     * 앱이 커서 없이 `?cursor=` 를 붙여 보내도 첫 진입이 깨지지 않는다.
     *
     * 숫자가 아니면 [SnackbarException] — `Long?` 으로 바인딩했다면 HTTP 500 이 나갔을 자리다
     * (클래스 KDoc).
     */
    private fun parseCursor(raw: String?): Long? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.toLongOrNull() ?: throw SnackbarException("목록 위치가 올바르지 않아요")
    }

    /**
     * `?size=20` → `20`. 값이 없거나 빈 문자열이면 [DEFAULT_SIZE].
     *
     * ## 🔴 범위를 벗어나면 clamp 하지 않고 **거부한다**
     *
     * `size=100` 을 조용히 50 으로 깎으면 앱은 **100건을 받았다고 믿는다.** 그러면 마지막 항목이
     * 자기가 아는 100번째가 아니라 실제 50번째가 되고, 앱이 자체 계산한 커서로 다음 페이지를
     * 이어 붙이는 순간 **50건이 통째로 사라진다** — 예외도 로그도 없이. 거부하면 앱이 즉시 안다.
     * 보관함이 미래 달을 이번 달로 clamp 하지 않는 것과 같은 판단이다
     * (`ChallengeHistoryService.getHistories`: *"조용히 clamp 하면 응답 에코가 요청과 어긋난다"*).
     */
    private fun parseSize(raw: String?): Int {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT_SIZE
        val parsed = trimmed.toIntOrNull() ?: throw SnackbarException(INVALID_SIZE_MESSAGE)
        if (parsed < MIN_SIZE || parsed > MAX_SIZE) {
            throw SnackbarException(INVALID_SIZE_MESSAGE)
        }
        return parsed
    }

    /**
     * `RankingController` / `ChallengeHistoryController` 와 **같은 규칙**이다. principal 은 userId(Long).
     *
     * ⚠️ 세 컨트롤러에 같은 코드가 있는 것은 이 레포의 기존 패턴이다 — 공용 헬퍼로 묶는 것은
     * 이번 범위 밖이라 선례를 그대로 따른다.
     */
    private fun currentUserId(): Long {
        val auth = SecurityContextHolder.getContext().authentication
            ?: throw UnauthorizedException("인증이 필요합니다")
        val principal = auth.principal
        return when (principal) {
            is Long -> principal
            is Number -> principal.toLong()
            is String -> principal.toLongOrNull()
                ?: throw UnauthorizedException("인증 정보가 올바르지 않습니다")
            else -> throw UnauthorizedException("인증 정보가 올바르지 않습니다")
        }
    }

    private fun NotificationView.toDto(): NotificationDto = NotificationDto(
        notificationId = notificationId,
        // 🔴 서비스가 이미 `String` 으로 접어 뒀다 — enum 으로 되돌리지 마라 (NotificationDto KDoc).
        type = type,
        // 🔴 title 은 없다 — 서비스 뷰에도 없고 wire 에도 없다 (NotificationDto KDoc).
        // DB 의 title 컬럼은 그대로다. 화면이 그리지 않을 뿐이다.
        body = body,
        referenceId = referenceId,
        // createdAt 은 이미 KST LocalDateTime (ADR-0010) — @JsonFormat 이 wire 형식을 정한다.
        createdAt = createdAt,
    )

    companion object {
        /** `?size=` 생략 시 기본 건수. */
        private const val DEFAULT_SIZE = 20

        /** 한 번에 받을 수 있는 최소 건수. `0` 은 "아무것도 안 준다" 라 요청 자체가 무의미하다. */
        private const val MIN_SIZE = 1

        /** 한 번에 받을 수 있는 최대 건수. 초과분은 clamp 가 아니라 거부다 ([parseSize]). */
        private const val MAX_SIZE = 50

        private const val INVALID_SIZE_MESSAGE = "한 번에 받을 개수가 올바르지 않아요"
    }
}
