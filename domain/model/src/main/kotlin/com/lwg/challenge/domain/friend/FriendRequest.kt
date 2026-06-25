package com.lwg.challenge.domain.friend

import java.time.LocalDateTime

/**
 * 받은 친구 요청 한 건. PENDING friendships row + requester 의 user 정보.
 *
 * `id` 는 `friendships.id` (PENDING). 수락/거절 호출에 그대로 사용된다.
 * `requestedAt` 는 `friendships.created_at` — 받은 요청 목록은 `created_at DESC` 정렬.
 */
data class FriendRequest(
    val id: Long,
    val fromUserId: Long,
    val fromUserNickname: String,
    val fromUserProfileImageUrl: String?,
    val requestedAt: LocalDateTime,
)
