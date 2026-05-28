package com.lwg.challenge.controller.auth.dto

import com.lwg.challenge.controller.common.BaseResponse
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Access Token 재발급 응답")
data class RefreshResponse(
    val data: RefreshData,
) : BaseResponse()

@Schema(description = "재발급 데이터 (rotation: 새 access + 새 refresh 함께 발급)")
data class RefreshData(
    @field:Schema(example = "eyJhbGciOi...")
    val accessToken: String,
    @field:Schema(
        example = "eyJhbGciOi...",
        description = "회전된 새 refresh token. 클라이언트는 로컬 저장소를 이 값으로 덮어써야 한다.",
    )
    val refreshToken: String,
)
