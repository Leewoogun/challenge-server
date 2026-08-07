package com.lwg.challenge.infra.repositoryimpl.notification

import com.lwg.challenge.domain.notification.Notification
import com.lwg.challenge.domain.notification.NotificationRepository
import com.lwg.challenge.infra.entity.notification.NotificationEntity
import com.lwg.challenge.infra.jpa.notification.NotificationJpaRepository
import org.springframework.stereotype.Component

@Component
class NotificationRepositoryImpl(
    private val jpa: NotificationJpaRepository,
) : NotificationRepository {

    override fun save(notification: Notification): Notification =
        jpa.save(NotificationEntity.fromDomain(notification)).toDomain()
}
