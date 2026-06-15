package com.lwg.challenge.controller.record.dto

import com.lwg.challenge.controller.common.BaseResponse
import io.swagger.v3.oas.annotations.media.Schema

/**
 * GET /api/v1/record 응답.
 *
 * - `error=false`, `code=200`, `data` 에 사용자 누적 전적.
 * - 토큰 만료 / 인프라 에러는 `GlobalExceptionHandler` / `UnauthorizedEntryPoint` 가 BaseResponse 직접 반환.
 */
@Schema(description = "전적 응답 (BaseResponse 상속)")
data class RecordResponse(
    val data: RecordData,
) : BaseResponse()

@Schema(description = "사용자 누적 전적. 모바일 호환 필드명(win/lose/draw/currentStreak).")
data class RecordData(
    @field:Schema(example = "7")
    val win: Int,
    @field:Schema(example = "3")
    val lose: Int,
    @field:Schema(example = "2")
    val draw: Int,
    @field:Schema(example = "3")
    val currentStreak: Int,
)
