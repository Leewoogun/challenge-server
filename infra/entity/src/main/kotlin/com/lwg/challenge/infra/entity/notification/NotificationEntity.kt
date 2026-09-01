package com.lwg.challenge.infra.entity.notification

import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.notification.Notification
import com.lwg.challenge.domain.notification.NotificationType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * `notifications` 테이블 JPA 매핑. V1 스키마 그대로이며 **스키마 변경이 없다** —
 * V8 은 컬럼 주석만 정정했다 (push-fcm T-B3).
 *
 * `type` 은 `VARCHAR(30)` 에 [NotificationType] 의 enum name 을 그대로 저장한다.
 * `created_at` 은 KST 벽시계 (ADR-0010, V6 주석).
 */
@Entity
@Table(name = "notifications")
class NotificationEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "type", nullable = false, length = 30)
    var type: String,

    @Column(name = "title", nullable = false, length = 100)
    var title: String,

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    var body: String,

    /** 타입에 따라 참조 대상이 다르다. FK 제약이 없는 이유 (V8 컬럼 주석). */
    @Column(name = "reference_id")
    var referenceId: Long? = null,

    @Column(name = "is_read", nullable = false)
    var isRead: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = KstTime.now(),
) {

    /**
     * ⚠️ 알 수 없는 `type` 문자열은 **예외를 던지지 않고** [NotificationType.CHALLENGE_REQUEST] 로
     * 떨어진다 — `VerificationEntity.toDomain()` 과 같은 코드 모양이다. 폐기된 `SIGN_REQUEST` 가
     * 담긴 옛 row 를 읽다가 목록 조회 전체가 깨지는 것을 막는다.
     *
     * ## 오늘 강등이 안 터지는 이유 — 행이 없어서가 아니라 쓰기가 제약돼서다
     *
     * push-fcm 이 4종을 발송하며 `notifications` 에 row 를 쌓고 있다. **"0행이라 안전"은 더 이상
     * 성립하지 않고, 애초에 행 수는 근거가 못 된다.** 진짜 근거는 쓰기 경로다 — `fromDomain` 이
     * `type = n.type.name` 만 쓰므로 **DB 에 들어가는 값은 [NotificationType] 8종의 name 뿐이고,
     * 8종은 전부 round-trip 한다.** 그래서 강등이 실제로 걸리려면 **수동 DB 삽입**이거나
     * **구버전 인스턴스가 신버전이 쓴 row 를 읽는 롤링 배포**여야 한다 (현재 단일 인스턴스).
     *
     * ## 🔴 읽기 경로가 생기면서 이 fallback 의 성격이 바뀌었다
     *
     * notification-list 로 **목록 조회가 생겼다.** 이제 이 함수는 **저장된 모든 row** 를 읽고,
     * 그 결과가 **사용자 화면에 그대로 렌더되고 탭 목적지가 된다.** 그래서
     * [NotificationType.CHALLENGE_REQUEST] 는 **실재하고 탭되는 타입이라 나쁜 fallback 이다** —
     * 모르는 알림이 *"도전장이 왔다"* 로 **없던 사실을 화면에 찍고**, 탭하면 그 챌린지가 아닌 곳으로
     * 간다. 조용히 틀리고 **앱은 알 방법이 없다: 받은 값 자체가 이미 거짓이라 방어할 표면이 없다.**
     * 앱은 *"모르는 타입 → 무채색 아이콘 + 비탭"* 폴백을 갖고 있는데,
     * **서버가 그 앞에서 아는 값으로 바꿔 버려 폴백이 작동하지 못한다.**
     *
     * ⚠️ `VerificationEntity.toDomain()` 과 **같은 것은 코드 모양까지고 위험 등급은 다르다.** 거기서
     * `status` 는 **내부 분기용**이라 잘못 떨어지면 로직이 눈에 띄게 깨진다. 여기서는 **사용자에게
     * 렌더되는 값**이라 아무도 모르게 틀린다.
     *
     * ## 지금 고치지 않는 이유
     *
     * 고치려면 도메인 `Notification.type` 을 String 으로 바꾸거나 `UNKNOWN` 을 추가해야 하는데,
     * 발송부(`NotificationDispatcher` · `NotificationMessages.of`)와 그 테스트까지 번진다.
     * **오늘 도달 경로가 없어서(위 쓰기 제약) 관측 전에는 안 고친다**가 방침이다.
     * 트리거 2개 — (1) 다중 인스턴스 배포(ADR-0007), (2) enum 밖 타입이 DB 에 들어가는 경로 발생.
     * 참조: `docs/features/notification-list/backend-report.md` 미해결 1 · `api-contract.md` §5.
     */
    fun toDomain(): Notification = Notification(
        id = id,
        userId = userId,
        type = runCatching { NotificationType.valueOf(type) }
            .getOrDefault(NotificationType.CHALLENGE_REQUEST),
        title = title,
        body = body,
        referenceId = referenceId,
        isRead = isRead,
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(n: Notification): NotificationEntity = NotificationEntity(
            id = n.id,
            userId = n.userId,
            type = n.type.name,
            title = n.title,
            body = n.body,
            referenceId = n.referenceId,
            isRead = n.isRead,
            createdAt = n.createdAt,
        )
    }
}
