package com.lwg.challenge.domain.friend

import java.time.LocalDateTime

/**
 * 친구 목록 조회 결과 한 건. ACCEPTED friendships row 의 "상대편" 사용자 정보.
 *
 * `id` 는 상대(나 아닌 쪽)의 user id. `since` 는 KST `LocalDateTime` 이라 변환 없이 그대로 응답에 실린다 (ADR-0010).
 */
data class Friend(
    val id: Long,
    val nickname: String,
    val profileImageUrl: String?,
    /** `friendships.accepted_at`. 친구 목록은 `accepted_at DESC` 정렬. */
    val since: LocalDateTime,
)
