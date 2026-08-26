package com.lwg.challenge.controller.ranking.dto

import com.lwg.challenge.controller.common.BaseResponse
import io.swagger.v3.oas.annotations.media.Schema

/**
 * GET /api/v1/rankings/losers 응답.
 *
 * - `error=false`, `code=200`, `data.rankings` 에 나 + accepted 친구의 랭킹.
 * - 토큰 만료 / 인프라 에러는 `GlobalExceptionHandler` / `UnauthorizedEntryPoint` 가 BaseResponse 직접 반환.
 */
@Schema(description = "개돼지 랭킹 응답 (BaseResponse 상속)")
data class LoserRankingResponse(
    val data: LoserRankingData,
) : BaseResponse()

@Schema(description = "개돼지 랭킹 목록")
data class LoserRankingData(
    @field:Schema(
        description = "패배 기준 내림차순 정렬 완료 목록. **절대 비지 않는다** — 나는 항상 포함되므로 최소 1건이고, " +
            "친구가 0명이면 정확히 1건(나)이다.",
    )
    val rankings: List<LoserRankingItem>,
)

@Schema(description = "랭킹 1건. 정렬·순위·패배율 모두 서버 계산 결과다.")
data class LoserRankingItem(
    @field:Schema(description = "1부터 연속 고유값. 공동 순위 없음.", example = "1")
    val rank: Int,
    @field:Schema(example = "3")
    val userId: Long,
    @field:Schema(example = "준혁")
    val nickname: String,
    @field:Schema(example = "https://cdn.example.com/p/3.jpg")
    val profileImageUrl: String?,
    @field:Schema(
        description = "목록 중 정확히 1건만 true. 앱은 이 값으로 '나' 를 식별한다 (userId 대조 아님).",
        example = "false",
    )
    val isMe: Boolean,
    @field:Schema(example = "25")
    val losses: Int,
    @field:Schema(
        description = "0~100 정수 %. 서버 계산·서버 정렬 기준이며 **앱이 재계산하지 않는다**. 챌린지 0회면 0.",
        example = "78",
    )
    val lossRate: Int,
    @field:Schema(example = "7")
    val currentLossStreak: Int,
    @field:Schema(
        description = "누적 챌린지 횟수. **정렬 키가 아니라 표시 분기 전용**이다 — 0 이면 앱이 캡션을 " +
            "'아직 기록 없음' 으로 대체한다(챌린지 0회와 전승 0패가 losses·lossRate 만으로는 구분되지 않으므로). " +
            "**이 값으로 lossRate 를 재계산하지 않는다** — 서버가 반올림한 정수를 정렬 키로도 쓰고 있어 " +
            "앱이 다시 나누면 표시와 정렬이 갈릴 수 있다.",
        example = "32",
    )
    val totalChallenges: Int,
)
