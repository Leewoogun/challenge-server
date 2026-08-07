package com.lwg.challenge.domain.notification

/**
 * 알림 영속성 추상. 구현체는 `:infra:repositoryimpl` 의 `NotificationRepositoryImpl`.
 *
 * 조회는 아직 없다 — 알림 목록 화면이 이번 범위 밖이라 **쌓기만 한다** (spec 비범위 표).
 * 화면이 붙을 때 `findByUserId(...)` 계열이 여기에 추가된다.
 */
interface NotificationRepository {

    /** 알림 1건 저장. `notification.id == null` ⇒ INSERT 후 id 가 채워진 값을 반환. */
    fun save(notification: Notification): Notification
}
