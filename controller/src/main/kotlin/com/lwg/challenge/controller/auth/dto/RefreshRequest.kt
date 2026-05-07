package com.lwg.challenge.controller.auth.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "Access Token 재발급 요청")
data class RefreshRequest(
    @field:NotBlank(message = "refreshToken은 필수입니다")
    @field:Schema(description = "발급받은 Refresh Token")
    val refreshToken: String,
)
