package com.lwg.challenge.controller.user.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * PUT /api/v1/users/me/fcm-token 요청 본문 (push-fcm 계약 §1).
 *
 * blank 는 `@NotBlank` 로 걷어낸다 — GlobalExceptionHandler 가 HTTP 200 + code 700 으로 바꾼다.
 * **토큰 제거는 이 엔드포인트가 아니라 `DELETE /auth/logout` 이 한다.** 빈 문자열을 "제거"로
 * 해석하면 모바일의 버그(토큰 획득 실패 시 빈 값 전송)가 조용히 알림 해제로 둔갑한다.
 */
@Schema(description = "FCM 토큰 등록·갱신 본문")
data class FcmTokenRequest(
    @field:NotBlank(message = "FCM 토큰이 비어 있어요")
    @field:Size(max = MAX_LENGTH, message = "FCM 토큰 길이가 비정상입니다")
    @field:Schema(
        description = "FCM 등록 토큰. 모바일이 로그인 직후와 onNewToken 양쪽에서 보낸다.",
        example = "dGhpcyBpcyBhIGZha2UgdG9rZW4...",
    )
    val fcmToken: String,
) {
    companion object {
        /**
         * `users.fcm_token` 은 `TEXT` 라 DB 상한이 없다. 그래도 상한을 두는 이유는 **길이 제약이
         * 아니라 입력 방어**다 — 무제한 문자열을 그대로 받아 저장하면 요청 하나로 임의 크기를
         * 밀어넣을 수 있다. 실제 FCM 등록 토큰은 150~250자 수준이라 20배 가까운 여유다.
         */
        const val MAX_LENGTH = 4096
    }
}
