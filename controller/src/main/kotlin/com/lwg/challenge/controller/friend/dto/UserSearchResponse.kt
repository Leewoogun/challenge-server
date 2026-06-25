package com.lwg.challenge.controller.friend.dto

import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.domain.friend.Relation
import io.swagger.v3.oas.annotations.media.Schema

/**
 * GET /api/v1/users/search 응답 (BaseResponse 상속).
 *
 * `users` 는 빈 배열로 반환 (null 금지) — 모바일이 길이 0 으로 판정.
 */
@Schema(description = "닉네임 검색 응답")
data class UserSearchResponse(
    val data: UserSearchData,
) : BaseResponse()

@Schema(description = "검색 결과 묶음")
data class UserSearchData(
    val users: List<UserSearchItem>,
)

@Schema(description = "검색 결과 한 건")
data class UserSearchItem(
    @field:Schema(example = "42")
    val id: Long,

    @field:Schema(example = "민수")
    val nickname: String,

    @field:Schema(example = "https://cdn.example.com/u/42.jpg")
    val profileImageUrl: String?,

    @field:Schema(
        description = "친구 관계 derived 값. UPPER_SNAKE_CASE 직렬화.",
        example = "REQUEST_SENT",
    )
    val relation: Relation,

    @field:Schema(
        description = "REQUEST_SENT/REQUEST_RECEIVED 일 때 PENDING friendships row id. 그 외 null.",
        example = "901",
    )
    val pendingRequestId: Long?,
)
