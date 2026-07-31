package com.lwg.challenge.controller.challenge

import com.lwg.challenge.controller.challenge.dto.AcceptChallengeBody
import com.lwg.challenge.controller.challenge.dto.AcceptChallengeResponse
import com.lwg.challenge.controller.challenge.dto.CancelChallengeData
import com.lwg.challenge.controller.challenge.dto.CancelChallengeResponse
import com.lwg.challenge.controller.challenge.dto.ChallengeCreateData
import com.lwg.challenge.controller.challenge.dto.ChallengeCreateResponse
import com.lwg.challenge.controller.challenge.dto.ChallengeStatusData
import com.lwg.challenge.controller.challenge.dto.CreateChallengeBody
import com.lwg.challenge.controller.challenge.dto.ReceivedChallengeItem
import com.lwg.challenge.controller.challenge.dto.ReceivedChallengesData
import com.lwg.challenge.controller.challenge.dto.ReceivedChallengesResponse
import com.lwg.challenge.controller.challenge.dto.RejectChallengeResponse
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ReceivedChallenge
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.service.challenge.ChallengeCommandService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 챌린지 신청 / 수락 / 거절 / 취소 컨트롤러 (challenge-create api-contract §1~5).
 *
 * 조회 전용 `GET /challenges/active` 는 [ActiveChallengeController] (home-feed) 가 계속 담당한다.
 * 같은 `/api/v1/challenges` 네임스페이스를 공유하지만 기존 응답 shape 은 건드리지 않는다.
 *
 * 인증은 SecurityFilterChain `.anyRequest().authenticated()` + `JwtAuthenticationFilter` 담당.
 * 정상 케이스 principal 은 userId(Long).
 *
 * **시간 직렬화**: DB 의 `LocalDateTime` 은 tz 없는 UTC 기준 값이라 `toInstant(ZoneOffset.UTC)` 로 변환한다.
 * `createdAt` 은 `LocalDateTime.now()` 유래라 나노초가 붙으므로 **초 단위로 절삭**해 내보낸다 —
 * 모바일이 `Instant.parse()` 실패 시 `Instant.DISTANT_PAST` 로 조용히 폴백하기 때문에 표기를 고정한다.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Challenge", description = "챌린지 신청 / 수락 / 거절 / 취소")
class ChallengeCommandController(
    private val challengeCommandService: ChallengeCommandService,
) {

    @PostMapping("/challenges")
    @Operation(
        summary = "챌린지 신청 (PENDING 생성)",
        description = "친구에게 1:1 챌린지를 건다. 본인 불가 / ACCEPTED 친구만 / 같은 날짜 양방향 중복 불가. " +
            "opponent_mission 은 null 로 생성되고 상대가 수락하며 채운다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun create(
        @Valid @RequestBody body: CreateChallengeBody,
    ): ChallengeCreateResponse {
        val me = currentUserId()
        val created: Challenge = challengeCommandService.create(
            me = me,
            opponentId = body.opponentId ?: throw SnackbarException(MSG_OPPONENT_REQUIRED),
            myMission = body.myMission.orEmpty(),
            betContent = body.betContent.orEmpty(),
            deadlineType = body.deadlineType ?: throw SnackbarException(MSG_DEADLINE_REQUIRED),
        )
        return ChallengeCreateResponse(
            data = ChallengeCreateData(
                challengeId = created.id ?: error("저장된 challenge 의 id 가 null 일 수 없습니다"),
                status = created.status,
                challengeDate = created.challengeDate,
                deadline = created.deadline.toInstant(ZoneOffset.UTC),
            ),
        )
    }

    @GetMapping("/challenges/received")
    @Operation(
        summary = "받은 도전장 목록 (PENDING + 마감 미경과)",
        description = "내가 opponent 인 PENDING 챌린지를 created_at DESC 로 반환. " +
            "마감 지난 건은 DB 에서 PENDING 인 채로 두고 응답에서만 제외한다 (lazy expiry).",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun listReceived(): ReceivedChallengesResponse {
        val me = currentUserId()
        val received: List<ReceivedChallenge> = challengeCommandService.listReceived(me)
        return ReceivedChallengesResponse(
            data = ReceivedChallengesData(challenges = received.map { it.toDto() }),
        )
    }

    @PostMapping("/challenges/{id}/accept")
    @Operation(
        summary = "받은 도전장 수락 + 내 미션 입력 → IN_PROGRESS",
        description = "opponent 만 가능 + PENDING 만 가능 + 마감 전만 가능. " +
            "단일 트랜잭션으로 status 전이와 verifications PENDING row 2건 생성이 함께 일어난다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun accept(
        @PathVariable("id") id: Long,
        @Valid @RequestBody body: AcceptChallengeBody,
    ): AcceptChallengeResponse {
        val me = currentUserId()
        val accepted: Challenge = challengeCommandService.accept(
            me = me,
            challengeId = id,
            myMission = body.myMission.orEmpty(),
        )
        return AcceptChallengeResponse(
            data = ChallengeStatusData(
                challengeId = accepted.id ?: error("저장된 challenge 의 id 가 null 일 수 없습니다"),
                status = accepted.status,
            ),
        )
    }

    @PostMapping("/challenges/{id}/reject")
    @Operation(
        summary = "받은 도전장 거절 → REJECTED",
        description = "opponent 만 가능 + PENDING 만 가능 + 마감 전만 가능. row 는 보존한다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun reject(
        @PathVariable("id") id: Long,
    ): RejectChallengeResponse {
        val me = currentUserId()
        val rejected: Challenge = challengeCommandService.reject(me = me, challengeId = id)
        return RejectChallengeResponse(
            data = ChallengeStatusData(
                challengeId = rejected.id ?: error("저장된 challenge 의 id 가 null 일 수 없습니다"),
                status = rejected.status,
            ),
        )
    }

    @DeleteMapping("/challenges/{id}")
    @Operation(
        summary = "챌린저가 보낸 PENDING 챌린지 취소 (물리 삭제)",
        description = "challenger 만 가능 + PENDING 만 가능. 마감 경과는 막지 않는다 (내 도전장 정리 목적).",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun cancel(
        @PathVariable("id") id: Long,
    ): CancelChallengeResponse {
        val me = currentUserId()
        challengeCommandService.cancel(me = me, challengeId = id)
        return CancelChallengeResponse(data = CancelChallengeData(challengeId = id))
    }

    // ─── helpers ─────────────────────────────────────────────────

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

    private fun ReceivedChallenge.toDto(): ReceivedChallengeItem = ReceivedChallengeItem(
        challengeId = challengeId,
        challengerId = challengerId,
        challengerNickname = challengerNickname,
        challengerProfileImageUrl = challengerProfileImageUrl,
        challengerMission = challengerMission,
        betContent = betContent,
        challengeDate = challengeDate,
        deadline = deadline.toInstant(ZoneOffset.UTC),
        // 나노초 자릿수가 섞이지 않도록 초 단위 절삭 (api-contract 확정).
        createdAt = createdAt.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS),
    )

    companion object {
        // @NotNull 이 먼저 걸리므로 정상 경로에서는 도달하지 않지만, 방어적으로 같은 문구를 쓴다.
        private const val MSG_OPPONENT_REQUIRED = "상대를 찾을 수 없어요"
        private const val MSG_DEADLINE_REQUIRED = "마감을 선택해주세요"
    }
}
