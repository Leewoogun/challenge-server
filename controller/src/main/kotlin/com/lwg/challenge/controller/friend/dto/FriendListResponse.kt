package com.lwg.challenge.controller.friend.dto

import com.lwg.challenge.controller.common.BaseResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * GET /api/v1/friends 응답 (BaseResponse 상속).
 *
 * `friends` 는 빈 배열로 반환 (null 금지).
 */
@Schema(description = "친구 목록 응답")
data class FriendsResponse(
    val data: FriendsData,
) : BaseResponse()

@Schema(description = "친구 목록 데이터")
data class FriendsData(
    val friends: List<FriendItem>,
)

@Schema(description = "친구 1건. id 는 상대 user id.")
data class FriendItem(
    @field:Schema(example = "42")
    val id: Long,
    @field:Schema(example = "민수")
    val nickname: String,
    @field:Schema(example = "https://cdn.example.com/u/42.jpg")
    val profileImageUrl: String?,
    @field:Schema(description = "친구가 된 시각 (= friendships.accepted_at). ISO-8601 UTC.")
    val since: Instant,
)

/**
 * GET /api/v1/friends/requests/received 응답.
 */
@Schema(description = "받은 요청 목록 응답")
data class ReceivedFriendRequestsResponse(
    val data: ReceivedFriendRequestsData,
) : BaseResponse()

@Schema(description = "받은 요청 목록 데이터")
data class ReceivedFriendRequestsData(
    val requests: List<ReceivedFriendRequestItem>,
)

@Schema(description = "받은 친구 요청 1건. id 는 friendships.id (PENDING).")
data class ReceivedFriendRequestItem(
    @field:Schema(example = "901")
    val id: Long,
    val fromUser: FromUser,
    @field:Schema(description = "요청 시각 (= friendships.created_at). ISO-8601 UTC.")
    val requestedAt: Instant,
)

@Schema(description = "요청 발신자 요약")
data class FromUser(
    @field:Schema(example = "42")
    val id: Long,
    @field:Schema(example = "민수")
    val nickname: String,
    @field:Schema(example = "https://cdn.example.com/u/42.jpg")
    val profileImageUrl: String?,
)
