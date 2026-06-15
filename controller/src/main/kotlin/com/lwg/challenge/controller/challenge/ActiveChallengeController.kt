package com.lwg.challenge.controller.challenge

import com.lwg.challenge.controller.challenge.dto.ActiveChallengeDto
import com.lwg.challenge.controller.challenge.dto.ActiveChallengeListData
import com.lwg.challenge.controller.challenge.dto.ActiveChallengeResponse
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.service.challenge.ActiveChallengeService
import com.lwg.challenge.service.challenge.ActiveChallengeView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset

/**
 * 진행 중 챌린지 조회 컨트롤러.
 *
 * 인증은 SecurityFilterChain `.anyRequest().authenticated()` + `JwtAuthenticationFilter` 가 담당.
 * 정상 케이스에서는 SecurityContext 의 principal 이 userId(Long).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Challenge", description = "챌린지 조회")
class ActiveChallengeController(
    private val activeChallengeService: ActiveChallengeService,
) {

    @GetMapping("/challenges/active")
    @Operation(
        summary = "진행 중 챌린지 조회",
        description = "현재 사용자가 challenger 또는 opponent 인 IN_PROGRESS 챌린지를 deadline ASC 로 반환.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun getActiveChallenges(): ActiveChallengeResponse {
        val userId: Long = currentUserId()
        val views: List<ActiveChallengeView> = activeChallengeService.getActiveChallenges(userId)
        return ActiveChallengeResponse(
            data = ActiveChallengeListData(activeChallenges = views.map { it.toDto() }),
        )
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

    private fun ActiveChallengeView.toDto(): ActiveChallengeDto = ActiveChallengeDto(
        challengeId = challengeId,
        myMission = myMission,
        opponentNickname = opponentNickname,
        opponentMission = opponentMission,
        // deadline 은 LocalDateTime (DB TIMESTAMP, tz 없음). 백엔드는 UTC 로 저장 가정 → 그대로 Instant 변환.
        deadline = deadline.toInstant(ZoneOffset.UTC),
        myVerificationStatus = myVerificationStatus,
        opponentVerificationStatus = opponentVerificationStatus,
        bet = bet,
    )
}
