package com.lwg.challenge.api.auth.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 카카오 로그인 요청.
 *
 * CMP 모바일 앱이 WebView로 카카오 인가 페이지를 띄우고, redirect_uri에서 추출한
 * authorization code 만 전달한다. 서버가 code → access_token 교환을 수행하므로
 * client_secret 등 민감 키는 서버에만 존재한다.
 */
@Schema(description = "카카오 로그인 요청 (Authorization Code Flow)")
data class KakaoLoginRequest(
    @field:NotBlank(message = "카카오 인가 코드가 누락되었습니다")
    @field:Size(max = 1024, message = "카카오 인가 코드 길이가 비정상입니다")
    @field:Schema(
        description = "카카오 OAuth authorization code (모바일 WebView가 redirect_uri의 ?code= 에서 추출)",
        example = "abc123def456",
    )
    val code: String,
)
