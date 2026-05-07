package com.lwg.challenge.controller.auth.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "카카오 로그인 요청 (Kakao SDK access_token 전달)")
data class KakaoLoginRequest(
    @field:NotBlank(message = "카카오 토큰이 누락되었습니다")
    @field:Size(max = 2048, message = "카카오 토큰 길이가 비정상입니다")
    @field:Schema(
        description = "Kakao SDK가 모바일에서 획득한 access_token",
        example = "eyJ0eXAiOiJKV1QiLCJhbGciOi...",
    )
    val kakaoAccessToken: String,
)
