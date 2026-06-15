package com.lwg.challenge.controller.challenge.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.domain.verification.VerificationStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * GET /api/v1/challenges/active 응답.
 *
 * - `error=false`, `code=200`, `data.activeChallenges` 에 진행 중 챌린지 리스트.
 * - 토큰 만료 / 인프라 에러는 `GlobalExceptionHandler` / `UnauthorizedEntryPoint` 가 BaseResponse 직접 반환.
 */
@Schema(description = "진행 중 챌린지 응답 (BaseResponse 상속)")
data class ActiveChallengeResponse(
    val data: ActiveChallengeListData,
) : BaseResponse()

@Schema(description = "진행 중 챌린지 리스트")
data class ActiveChallengeListData(
    val activeChallenges: List<ActiveChallengeDto>,
)

@Schema(description = "현재 사용자 시점에서 정리된 진행 중 챌린지 1건.")
data class ActiveChallengeDto(
    @field:Schema(example = "1001")
    val challengeId: Long,
    @field:Schema(example = "오늘 운동 1시간 하기")
    val myMission: String,
    @field:Schema(example = "민수")
    val opponentNickname: String,
    @field:Schema(example = "책 30페이지 읽기")
    val opponentMission: String,
    /** ISO-8601 UTC (예: `"2026-05-26T00:00:00Z"`). 모바일에서 상대 시간 변환. */
    @field:Schema(example = "2026-05-26T00:00:00Z", description = "ISO-8601 UTC")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val deadline: Instant,
    @field:Schema(example = "PENDING")
    val myVerificationStatus: VerificationStatus,
    @field:Schema(example = "VERIFIED")
    val opponentVerificationStatus: VerificationStatus,
    @field:Schema(example = "커피 사기")
    val bet: String,
)
