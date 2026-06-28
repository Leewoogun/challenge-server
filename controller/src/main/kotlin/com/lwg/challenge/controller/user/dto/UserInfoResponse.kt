package com.lwg.challenge.controller.user.dto

import com.lwg.challenge.controller.common.BaseResponse

/**
 * GET /api/v1/users/me 응답 (api-contract-user-info §1).
 *
 * BaseResponse 패턴: 성공 시 error=false, code=200, data 에 본인 정보 4 필드.
 */
data class UserInfoResponse(
    val data: UserInfoData,
) : BaseResponse()

data class UserInfoData(
    val id: Long,
    val kakaoId: Long,
    val nickname: String,
    val profileImageUrl: String?,
)
