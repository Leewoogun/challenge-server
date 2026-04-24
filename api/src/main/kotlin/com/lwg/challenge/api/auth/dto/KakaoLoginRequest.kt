package com.lwg.challenge.api.auth.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "카카오 로그인 요청")
data class KakaoLoginRequest(
    @field:NotBlank(message = "kakaoAccessToken은 필수입니다")
    @field:Schema(description = "Kakao SDK가 반환한 OAuth access token", example = "abc123...")
    val kakaoAccessToken: String,

    @field:Schema(
        description = "Kakao account_phone_number scope 동의 시 전화번호 (E.164 포맷)",
        example = "+821012345678",
        nullable = true,
    )
    val phoneNumber: String? = null,
)
