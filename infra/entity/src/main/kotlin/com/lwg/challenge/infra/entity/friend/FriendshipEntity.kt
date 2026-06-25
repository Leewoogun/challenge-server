package com.lwg.challenge.infra.entity.friend

import com.lwg.challenge.domain.friend.Friendship
import com.lwg.challenge.domain.friend.FriendshipStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * `friendships` 테이블 JPA 매핑. V1__init.sql 스키마 그대로 사용 (마이그레이션 0건).
 *
 * - `requester_id` → `receiver_id` 단방향 1 row. 양방향 row 만들지 않음.
 * - `status` VARCHAR(20): PENDING / ACCEPTED / REJECTED / BLOCKED.
 * - `accepted_at` 은 ACCEPTED 일 때만 채워짐. PENDING / REJECTED 일 때는 null.
 * - UNIQUE(requester_id, receiver_id) 제약 — 동일 방향 중복 INSERT 차단.
 * - `created_at` 은 PrePersist 로 보존, UPDATE 시 건드리지 않음 (REJECTED → PENDING 재요청 audit trail).
 */
@Entity
@Table(name = "friendships")
class FriendshipEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "requester_id", nullable = false)
    var requesterId: Long,

    @Column(name = "receiver_id", nullable = false)
    var receiverId: Long,

    @Column(name = "status", nullable = false, length = 20)
    var status: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "accepted_at")
    var acceptedAt: LocalDateTime? = null,
) {

    fun toDomain(): Friendship = Friendship(
        id = id,
        requesterId = requesterId,
        receiverId = receiverId,
        status = runCatching { FriendshipStatus.valueOf(status) }.getOrDefault(FriendshipStatus.PENDING),
        createdAt = createdAt,
        acceptedAt = acceptedAt,
    )

    companion object {
        fun fromDomain(f: Friendship): FriendshipEntity = FriendshipEntity(
            id = f.id,
            requesterId = f.requesterId,
            receiverId = f.receiverId,
            status = f.status.name,
            createdAt = f.createdAt,
            acceptedAt = f.acceptedAt,
        )
    }
}
