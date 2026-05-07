package com.lwg.challenge.controller.auth.dto

import com.lwg.challenge.controller.common.BaseResponse
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "로그인 응답 (BaseResponse 상속)")
data class LoginResponse(
    val data: LoginData,
) : BaseResponse()

@Schema(description = "로그인 데이터")
data class LoginData(
    @field:Schema(example = "eyJhbGciOi...")
    val accessToken: String,
    @field:Schema(example = "eyJhbGciOi...")
    val refreshToken: String,
    @field:Schema(example = "1")
    val userId: Long,
    @field:Schema(description = "신규 가입 여부 (온보딩 유도용)")
    val isNewUser: Boolean,
)
