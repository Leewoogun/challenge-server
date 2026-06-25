package com.lwg.challenge.domain.friend

import java.time.LocalDateTime

/**
 * `friendships` 테이블의 도메인 모델. V1__init.sql 스키마 그대로.
 *
 * - `requesterId` → `receiverId` 단방향 1 row. 양방향 row 만들지 않음.
 * - `status` 4-state (PENDING / ACCEPTED / REJECTED / BLOCKED). BLOCKED 는 1차 미사용.
 * - `acceptedAt` 은 ACCEPTED 일 때만 채워짐. PENDING 또는 REJECTED 일 때는 null.
 * - `id == null` ⇒ 아직 영속화되지 않은 신규 row.
 */
data class Friendship(
    val id: Long?,
    val requesterId: Long,
    val receiverId: Long,
    val status: FriendshipStatus,
    val createdAt: LocalDateTime,
    val acceptedAt: LocalDateTime?,
)

enum class FriendshipStatus {
    PENDING,
    ACCEPTED,
    REJECTED,

    /** V1 스키마에는 존재하나 1차 friends feature 에서는 생성 경로 없음. */
    BLOCKED,
}
