package com.lwg.challenge.domain.friend

/**
 * 닉네임 검색 결과 한 건. 본인 제외, ACTIVE 사용자만.
 *
 * `relation` / `pendingRequestId` 는 friendships row 를 보고 service 가 계산한 derived 값.
 * - `pendingRequestId` 는 relation 이 `REQUEST_SENT` / `REQUEST_RECEIVED` 일 때만 non-null.
 *   - REQUEST_SENT  → 내가 보낸 PENDING row id (취소 호출에 사용)
 *   - REQUEST_RECEIVED → 상대가 보낸 PENDING row id (수락/거절 호출에 사용)
 * - 그 외 relation 에서는 null.
 *
 * 정렬은 service / repository 가 `u.nickname ASC, u.id ASC` 로 LIMIT 20 반환.
 */
data class UserSearchResult(
    val id: Long,
    val nickname: String,
    val profileImageUrl: String?,
    val relation: Relation,
    val pendingRequestId: Long?,
)
