package com.lwg.challenge.controller.notification.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.controller.common.WIRE_DATETIME
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * `GET /api/v1/notifications` 응답 (BaseResponse 상속).
 *
 * - `error=false`, `code=200`, `data.notifications` 에 최신순 알림 목록.
 * - 토큰 만료 / 인프라 에러는 `GlobalExceptionHandler` / `UnauthorizedEntryPoint` 가
 *   `BaseResponse` 를 직접 반환한다.
 */
@Schema(description = "알림 목록 응답 (BaseResponse 상속)")
data class NotificationListResponse(
    val data: NotificationListData,
) : BaseResponse()

/**
 * 알림 한 페이지.
 *
 * ⚠️ [nextCursor] 는 **null 이어도 키가 실려 나간다** — 이 프로젝트 Jackson 에
 * `@JsonInclude(NON_NULL)` 이 없기 때문이다. 모바일이 키 존재를 전제해도 되는 근거가 그것이고,
 * `NotificationControllerTest` 가 응답 **원문**으로 고정한다. 누가 전역 설정으로 NON_NULL 을
 * 켜면 목록 끝에서 키가 통째로 사라지고 앱의 실패 모드가 조용히 바뀐다.
 */
@Schema(description = "알림 목록 + 다음 커서")
data class NotificationListData(
    /** 최신순(`id` 내림차순). 알림이 없으면 **빈 배열**이다 (null 금지). */
    val notifications: List<NotificationDto>,
    /**
     * 다음 페이지 요청에 그대로 실을 커서. **다음이 없으면 `null`** 이고 그게 목록의 끝이다.
     * 값은 이 페이지 마지막 항목의 `notificationId` 다.
     */
    @field:Schema(example = "38", nullable = true)
    val nextCursor: Long?,
)

/**
 * `GET /api/v1/notifications/unread-count` **와** `POST /api/v1/notifications/read-all` 의
 * 공통 응답 (BaseResponse 상속).
 *
 * ## 🔴 두 엔드포인트가 같은 DTO 를 쓰는 것이 결정이다
 *
 * 둘이 답하는 것은 결국 **하나** — *"지금 안 읽은 게 몇 개냐"* 다. 응답 shape 이 갈리면 앱이
 * **뱃지 갱신 경로를 두 벌** 갖게 되고, 그 둘은 반드시 어느 순간 어긋난다.
 *
 * ## 🔴 read-all 이 `BaseResponse()` 만 주지 않고 개수를 돌려주는 이유
 *
 * 앱이 *"read-all 을 불렀으니 뱃지는 0"* 이라는 **규칙을 갖지 않게** 하기 위해서다. 홈 진입이든
 * read-all 직후든 앱은 **항상 서버가 준 [UnreadCountData.unreadCount] 하나만 보고** 뱃지를 켠다.
 * 그 규칙이 앱에 한 번 생기면, 나중에 서버가 *범위 지정* read-all(예: 특정 타입만, 특정 시점
 * 이전만)을 도입하는 날 앱에 박제된 사본이 **조용히 틀린다** — 서버는 정상 응답을 주고 있으므로
 * 아무 로그도 남지 않는다.
 */
@Schema(description = "안 읽은 알림 개수 응답 (BaseResponse 상속)")
data class UnreadCountResponse(
    val data: UnreadCountData,
) : BaseResponse()

/**
 * 안 읽은 알림 개수.
 *
 * `read-all` 응답에서도 이 값은 **서버가 실제로 다시 센 결과**다 — 상수 0 이 아니다
 * (`NotificationCommandService.markAllAsRead` KDoc).
 */
@Schema(description = "안 읽은 알림 개수")
data class UnreadCountData(
    /** 0 이상. **0 은 정상**이고, 뱃지를 끄라는 뜻이다. */
    @field:Schema(example = "3")
    val unreadCount: Int,
)

/**
 * 알림 1건.
 *
 * ## 🔴 [type] 을 `NotificationType` enum 으로 선언하지 마라
 *
 * enum 으로 두어도 **wire 로 나가는 바이트는 똑같다** (Jackson 이 enum name 을 문자열로 쓴다).
 * 다른 것은 **SpringDoc 이 내는 OpenAPI 스키마**다 — enum 이면 `enum: [...]` 로 **닫힌 집합을
 * 광고**하고, 모바일은 그 스펙을 보고 typed enum 을 생성한다. 그 순간 계약이 *"이 8개 중 하나"* 가
 * 된다.
 *
 * 문제는 **서버가 값을 추가하는 사건이 가정이 아니라 예정**이라는 것이다. `NotificationType` 은
 * **8종이 선언돼 있지만 실제로 발송 가능한 것은 4종뿐**이고(나머지는 `NotificationMessages.of()`
 * 가 null 을 반환해 발송을 막는다), 남은 4종은 각 feature 가 붙을 때 열리기로 이미 정해져 있다.
 *
 * 그리고 이건 **목록**이라 피해가 한 건에 그치지 않는다. 새 타입이 섞인 행이 **하나**만 있어도
 * 배열 파싱이 그 지점에서 실패해 **응답 전체의 역직렬화가 깨지고 알림 화면이 통째로 빈다.**
 * 구버전 앱은 스토어 업데이트 전까지 그 상태로 남는다.
 *
 * 그래서 `String` 이다 — 앱은 아는 값만 아이콘·딥링크로 매핑하고 **모르는 값은 기본 표시로
 * 흘려보낸다.** 모바일 레포 `ActiveChallengeResponse.kt` 의 `VerificationStatusDto` KDoc 이 같은
 * 결론을 이미 적어 뒀다.
 *
 * ## 🔴 `isRead` 필드가 없는 것은 **의도적 부재**다 — 읽음 처리가 붙은 지금도 그렇다
 *
 * 읽음 처리는 **행 단위가 아니라 *"목록을 열면 전부 읽음"*** 으로 확정됐다
 * (`NotificationCommandService` KDoc). 그래서 이 목록에 행별 `isRead` 를 실을 자리가 없다 —
 * 앱이 목록을 여는 순간 `read-all` 이 돌아 **전부 읽음이 되므로 값은 여전히 정보량 0** 이고,
 * 오히려 응답을 만든 시점과 앱이 그리는 시점 사이에서 **어긋난 값**이 된다. 안 읽음 상태를
 * 표현하는 축은 목록이 아니라 **홈 벨 뱃지 하나**이고, 그건 [UnreadCountData] 가 답한다.
 *
 * 🔴 *"이제 읽음 처리가 생겼으니 넣어도 되겠다"* 는 반대 결론이다. 행 단위 읽음을 **채택하지
 * 않은 것**이 결정이고, 이 필드를 되살리면 *"언제 읽음인가"* 라는 제품 결정이 함께 돌아온다.
 * (`notifications.is_read` 컬럼과 `Notification.isRead` 는 V1 부터 있다 — 없는 것은 **wire 뿐**이다.)
 *
 * ## 🔴 `title` 필드가 없는 것도 **의도적 부재**다 — 서버가 저장하지 않는 것이 아니다
 *
 * ⚠️ 먼저 오해부터 막는다: **`notifications.title` 컬럼은 그대로 있고, 값도 그대로 들어간다.**
 * 발송 시점 문구가 **박제**돼 남아 있고(`NotificationMessages` KDoc, push-fcm 오픈이슈 2) 푸시는
 * 그 값을 헤드라인으로 쓴다. 응답에 없는 것은 **저장하지 않아서가 아니라 화면이 그리지 않아서**다.
 *
 * design §3.3 판정: 알림 카드는 **[body] 단독 1행**이고 `title` 은 그리지 않는다. 실측 4종의
 * `title` 은 `영혼의 맹세` / `계약 완료.` / `ㅠㅠ` / `증거 도착` 인데 **푸시 헤드라인으로 설계된
 * 문구라 목록에서 홀로 서지 못한다** — 2행으로 만들면 카드 높이가 1.5배가 되고 얻는 정보가 `ㅠㅠ`
 * 다. 응답 shape 은 **앱이 실제로 그리는 것만** 싣는다 (spec T-B1).
 *
 * 화면이 2행을 원하게 되면 그때 되살리면 된다 — **필드 추가는 non-breaking** 이고, 값은 이미 DB 에
 * 있으므로 마이그레이션도 필요 없다. 지금 빼 두는 것이 무비용인 이유가 그것이다.
 */
@Schema(description = "알림 1건")
data class NotificationDto(
    @field:Schema(example = "57")
    val notificationId: Long,
    /** 🔴 **열린 집합**이다. 앱이 모르는 값이 올 수 있고, 그때도 파싱은 성공해야 한다 (위 KDoc). */
    @field:Schema(
        example = "CHALLENGE_ACCEPTED",
        description = "알림 종류. **열린 집합** — 서버가 값을 추가할 수 있으므로 앱은 모르는 값을 " +
            "기본 표시로 흘려보내야 한다. typed enum 으로 만들지 마라.",
    )
    val type: String,
    /** 🔴 카드가 그리는 **유일한 문구**다 — `title` 은 싣지 않는다 (위 KDoc). */
    @field:Schema(example = "민수님이 수락했습니다")
    val body: String,
    /**
     * 타입에 따라 참조 대상이 다르다 — 현재 `CHALLENGE_*` 3종은 전부 `challenges.id` 다.
     * ⚠️ **null 이어도 키는 실린다** ([NotificationListData] KDoc 과 같은 사정).
     */
    @field:Schema(example = "1001", nullable = true)
    val referenceId: Long?,
    /** KST 기준 (예: `"2026-09-01 13:42:07"`). 모바일에서 상대 시간 변환. ADR-0010. */
    @field:Schema(example = "2026-09-01 13:42:07", description = "KST 기준 yyyy-MM-dd HH:mm:ss")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val createdAt: LocalDateTime,
)
