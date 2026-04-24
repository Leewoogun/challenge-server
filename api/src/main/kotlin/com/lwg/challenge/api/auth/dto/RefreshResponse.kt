package com.lwg.challenge.api.auth.dto

import com.lwg.challenge.api.common.BaseResponse
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Access Token 재발급 응답")
data class RefreshResponse(
    val data: RefreshData,
) : BaseResponse()

@Schema(description = "재발급 데이터")
data class RefreshData(
    @field:Schema(example = "eyJhbGciOi...")
    val accessToken: String,
)
