package com.lwg.challenge.controller.friend.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive

/**
 * POST /api/v1/friends/requests 요청 본문.
 */
@Schema(description = "친구 요청 보내기 본문")
data class SendFriendRequestBody(
    @field:Positive(message = "receiverId 가 올바르지 않습니다")
    @field:Schema(example = "42")
    val receiverId: Long,
)
