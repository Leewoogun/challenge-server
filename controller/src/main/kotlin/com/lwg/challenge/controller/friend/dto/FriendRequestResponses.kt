package com.lwg.challenge.controller.friend.dto

import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.domain.friend.FriendshipStatus
import io.swagger.v3.oas.annotations.media.Schema

/**
 * POST /api/v1/friends/requests 응답. `status` 는 항상 "PENDING".
 */
@Schema(description = "친구 요청 보내기 응답")
data class SendFriendRequestResponse(
    val data: SendFriendRequestData,
) : BaseResponse()

@Schema(description = "친구 요청 보내기 데이터")
data class SendFriendRequestData(
    @field:Schema(example = "901")
    val requestId: Long,
    @field:Schema(example = "PENDING")
    val status: FriendshipStatus,
)

/**
 * POST /api/v1/friends/requests/{id}/accept 응답. `status` 는 항상 "ACCEPTED".
 */
@Schema(description = "친구 요청 수락 응답")
data class AcceptFriendRequestResponse(
    val data: AcceptFriendRequestData,
) : BaseResponse()

@Schema(description = "친구 요청 수락 데이터")
data class AcceptFriendRequestData(
    @field:Schema(example = "901")
    val friendshipId: Long,
    @field:Schema(example = "ACCEPTED")
    val status: FriendshipStatus,
)

/**
 * POST /api/v1/friends/requests/{id}/reject 응답. `status` 는 항상 "REJECTED".
 */
@Schema(description = "친구 요청 거절 응답")
data class RejectFriendRequestResponse(
    val data: RejectFriendRequestData,
) : BaseResponse()

@Schema(description = "친구 요청 거절 데이터")
data class RejectFriendRequestData(
    @field:Schema(example = "901")
    val requestId: Long,
    @field:Schema(example = "REJECTED")
    val status: FriendshipStatus,
)

/**
 * DELETE /api/v1/friends/requests/{id} 응답. row 물리 삭제 후 참조용 id 반환.
 */
@Schema(description = "친구 요청 취소 응답")
data class CancelFriendRequestResponse(
    val data: CancelFriendRequestData,
) : BaseResponse()

@Schema(description = "친구 요청 취소 데이터")
data class CancelFriendRequestData(
    @field:Schema(example = "901")
    val requestId: Long,
)
