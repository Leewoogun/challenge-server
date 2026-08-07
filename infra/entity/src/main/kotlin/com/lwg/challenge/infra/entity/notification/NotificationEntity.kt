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
     * 떨어진다 — `VerificationEntity.toDomain()` 과 같은 방침이다. 폐기된 `SIGN_REQUEST` 가 담긴
     * 옛 row 를 읽다가 목록 조회 전체가 깨지는 것을 막는다.
     * (현재 `notifications` 는 0행이라 실제로 그런 row 는 없다.)
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
