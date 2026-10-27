package com.lwg.challenge.infra.repositoryimpl.notification

import com.lwg.challenge.domain.notification.Notification
import com.lwg.challenge.domain.notification.NotificationRepository
import com.lwg.challenge.infra.entity.notification.NotificationEntity
import com.lwg.challenge.infra.jpa.notification.NotificationJpaRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

@Component
class NotificationRepositoryImpl(
    private val jpa: NotificationJpaRepository,
) : NotificationRepository {

    override fun save(notification: Notification): Notification =
        jpa.save(NotificationEntity.fromDomain(notification)).toDomain()

    /**
     * 🔴 [nextNotificationId] 유무 분기가 **여기**에 있는 이유는 `NotificationJpaRepository` KDoc 에 있다 —
     * JPQL 한 개로 `(:nextNotificationId IS NULL OR ...)` 를 쓰면 null 파라미터의 타입 추론이 갈린다.
     * 이 분기 덕에 두 쿼리의 파라미터는 항상 non-null 이다.
     *
     * `PageRequest.of(0, limit)` 는 **LIMIT 전용**이다. 정렬은 JPQL 의 `ORDER BY n.id DESC` 가
     * 이미 정했으므로 `Sort` 를 싣지 않는다.
     */
    override fun findPageByUserId(userId: Long, nextNotificationId: Long?, limit: Int): List<Notification> {
        val pageable = PageRequest.of(0, limit)
        val entities = if (nextNotificationId == null) {
            jpa.findFirstPageByUserId(userId, pageable)
        } else {
            jpa.findPageByUserIdFrom(userId, nextNotificationId, pageable)
        }
        return entities.map { it.toDomain() }
    }

    override fun countUnreadByUserId(userId: Long): Long =
        jpa.countUnreadByUserId(userId)

    override fun markAllAsReadByUserId(userId: Long): Int =
        jpa.markAllAsReadByUserId(userId)

    override fun deleteAllByUserId(userId: Long): Int =
        jpa.deleteAllByUserId(userId)
}
