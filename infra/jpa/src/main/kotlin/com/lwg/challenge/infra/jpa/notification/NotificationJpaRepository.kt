package com.lwg.challenge.infra.jpa.notification

import com.lwg.challenge.infra.entity.notification.NotificationEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * notifications 테이블 CRUD. 모듈 외부에 노출되지 않는다 (NotificationRepositoryImpl 만 사용).
 *
 * 조회 메서드가 없는 것은 의도다 — 알림 목록 화면이 이번 범위 밖이라 **쌓기만 한다.**
 * `idx_notifications_user_read_created` 인덱스는 V1 부터 있으므로 화면이 붙을 때 바로 쓸 수 있다.
 */
interface NotificationJpaRepository : JpaRepository<NotificationEntity, Long>
