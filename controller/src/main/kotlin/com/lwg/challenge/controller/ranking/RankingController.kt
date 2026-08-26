package com.lwg.challenge.controller.ranking

import com.lwg.challenge.controller.ranking.dto.LoserRankingData
import com.lwg.challenge.controller.ranking.dto.LoserRankingItem
import com.lwg.challenge.controller.ranking.dto.LoserRankingResponse
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.domain.ranking.LoserRankingEntry
import com.lwg.challenge.service.ranking.RankingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 개돼지 랭킹(기획 §3.4) 조회 컨트롤러.
 *
 * 인증은 SecurityFilterChain `.anyRequest().authenticated()` + `JwtAuthenticationFilter` 가 담당.
 * 정상 케이스에서는 SecurityContext 의 principal 이 userId(Long).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Ranking", description = "개돼지 랭킹 조회")
class RankingController(
    private val rankingService: RankingService,
) {

    @GetMapping("/rankings/losers")
    @Operation(
        summary = "개돼지 랭킹 조회",
        description = "나 + accepted 친구의 누적 전적을 패배 기준으로 줄 세워 반환. " +
            "정렬과 rank 부여는 전부 서버가 하며, 나는 항상 포함되므로 목록은 비지 않는다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun getLoserRanking(): LoserRankingResponse {
        val userId: Long = currentUserId()
        val rankings: List<LoserRankingEntry> = rankingService.getLoserRanking(userId)
        return LoserRankingResponse(data = LoserRankingData(rankings = rankings.map { it.toDto() }))
    }

    private fun currentUserId(): Long {
        val auth = SecurityContextHolder.getContext().authentication
            ?: throw UnauthorizedException("인증이 필요합니다")
        val principal = auth.principal
        return when (principal) {
            is Long -> principal
            is Number -> principal.toLong()
            is String -> principal.toLongOrNull()
                ?: throw UnauthorizedException("인증 정보가 올바르지 않습니다")
            else -> throw UnauthorizedException("인증 정보가 올바르지 않습니다")
        }
    }

    private fun LoserRankingEntry.toDto(): LoserRankingItem = LoserRankingItem(
        rank = rank,
        userId = userId,
        nickname = nickname,
        profileImageUrl = profileImageUrl,
        isMe = isMe,
        losses = losses,
        lossRate = lossRate,
        currentLossStreak = currentLossStreak,
        totalChallenges = totalChallenges,
    )
}
