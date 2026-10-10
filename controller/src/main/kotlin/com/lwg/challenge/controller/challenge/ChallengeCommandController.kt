package com.lwg.challenge.controller.challenge

import com.lwg.challenge.controller.challenge.dto.AcceptChallengeBody
import com.lwg.challenge.controller.challenge.dto.AcceptChallengeResponse
import com.lwg.challenge.controller.challenge.dto.CancelChallengeData
import com.lwg.challenge.controller.challenge.dto.ChallengeDetailData
import com.lwg.challenge.controller.challenge.dto.ChallengeDetailResponse
import com.lwg.challenge.controller.challenge.dto.ContractDto
import com.lwg.challenge.controller.challenge.dto.MyPartyDto
import com.lwg.challenge.controller.challenge.dto.PartyDto
import com.lwg.challenge.controller.challenge.dto.CancelChallengeResponse
import com.lwg.challenge.controller.challenge.dto.ChallengeCreateData
import com.lwg.challenge.controller.challenge.dto.ChallengeCreateResponse
import com.lwg.challenge.controller.challenge.dto.ChallengeStatusData
import com.lwg.challenge.controller.challenge.dto.CreateChallengeBody
import com.lwg.challenge.controller.challenge.dto.ForfeitChallengeData
import com.lwg.challenge.controller.challenge.dto.ForfeitChallengeResponse
import com.lwg.challenge.controller.challenge.dto.ReceivedChallengeItem
import com.lwg.challenge.controller.challenge.dto.ReceivedChallengeOpponentDto
import com.lwg.challenge.controller.challenge.dto.ReceivedChallengesData
import com.lwg.challenge.controller.challenge.dto.ReceivedChallengesResponse
import com.lwg.challenge.controller.challenge.dto.RejectChallengeResponse
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ReceivedChallenge
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.service.challenge.ChallengeCommandService
import com.lwg.challenge.service.challenge.ChallengeDetailService
import com.lwg.challenge.service.challenge.ChallengeForfeitService
import com.lwg.challenge.service.challenge.ForfeitView
import com.lwg.challenge.service.challenge.ChallengeDetailView
import com.lwg.challenge.domain.challenge.ChallengeStatus
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
 * **시간 직렬화 (ADR-0010)**: DB 의 `LocalDateTime` 이 이미 KST 라 **변환 없이 그대로** 내보낸다.
 * `createdAt` 만 나노초가 붙으므로 **초 단위로 절삭**한다 — 계약이 밀리초·나노초를 금지한다.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Challenge", description = "챌린지 신청 / 수락 / 거절 / 취소")
class ChallengeCommandController(
    private val challengeCommandService: ChallengeCommandService,
    private val challengeDetailService: ChallengeDetailService,
    private val challengeForfeitService: ChallengeForfeitService,
) {

    @GetMapping("/challenges/{id}")
    @Operation(
        summary = "챌린지 상세 (계약서 + 양측 서명)",
        description = "당사자만 조회 가능. 요청자 기준 me/opponent 로 반환한다 — " +
            "myResult 는 내 시점 결과, forfeitedBy 는 ME/OPPONENT. 미서명 필드는 null.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun getDetail(
        @PathVariable("id") id: Long,
    ): ChallengeDetailResponse {
        val me = currentUserId()
        val view: ChallengeDetailView = challengeDetailService.getDetail(me, id)
        return ChallengeDetailResponse(
            data = ChallengeDetailData(
                challengeId = view.challengeId,
                status = ChallengeStatus.valueOf(view.status),
                // 요청자 시점으로 뒤집기는 서비스가 이미 끝냈다. COMPLETED 가 아니면 null.
                myResult = view.myResult,
                forfeitedBy = view.forfeitedBy,
                challengeDate = view.challengeDate,
                deadline = view.deadline,
                betContent = view.betContent,
                me = MyPartyDto(
                    nickname = view.me.nickname,
                    profileImageUrl = view.me.profileImageUrl,
                    mission = view.me.mission,
                ),
                opponent = PartyDto(
                    userId = view.opponent.userId,
                    nickname = view.opponent.nickname,
                    profileImageUrl = view.opponent.profileImageUrl,
                    mission = view.opponent.mission,
                ),
                contract = view.contract?.let {
                    ContractDto(
                        content = it.content,
                        isFinalized = it.isFinalized,
                        mySignature = it.mySignature,
                        mySignedAt = it.mySignedAt,
                        opponentSignature = it.opponentSignature,
                        opponentSignedAt = it.opponentSignedAt,
                    )
                },
            ),
        )
    }

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
            signature = body.signature ?: throw SnackbarException(MSG_SIGNATURE_REQUIRED),
        )
        return ChallengeCreateResponse(
            data = ChallengeCreateData(
                challengeId = created.id ?: error("저장된 challenge 의 id 가 null 일 수 없습니다"),
                status = created.status,
                challengeDate = created.challengeDate,
                deadline = created.deadline,
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
            signature = body.signature ?: throw SnackbarException(MSG_SIGNATURE_REQUIRED),
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

    @PostMapping("/challenges/{id}/forfeit")
    @Operation(
        summary = "기권 → 즉시 COMPLETED (상대 승)",
        description = "당사자만 + IN_PROGRESS 만 + **내가 아직 인증하지 않았을 때만** 가능. " +
            "요청 본문은 없다 — 기권자는 JWT 의 주체다. " +
            "성공하면 자정 배치를 기다리지 않고 그 자리에서 판정·전적 집계까지 끝난다. " +
            "🔴 상대의 인증 여부와 무관하게 상대가 이긴다 (기권은 \"상대에게 승리를 준다\" 는 선언이다). " +
            "🔴 되돌릴 수 없고 멱등도 아니다 — 두 번째 요청은 COMPLETED 라 code 705 로 거부된다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun forfeit(
        @PathVariable("id") id: Long,
    ): ForfeitChallengeResponse {
        val me = currentUserId()
        val view: ForfeitView = challengeForfeitService.forfeit(me = me, challengeId = id)
        return ForfeitChallengeResponse(
            data = ForfeitChallengeData(
                challengeId = view.challengeId,
                status = view.status,
                result = view.result,
                myResult = view.myResult,
                // 판정 시각은 Clock 에서 나노초가 붙는다. 계약이 밀리초·나노초를 금지하므로
                // 다른 응답과 같은 자리에서 초 단위로 절삭한다.
                completedAt = view.completedAt.truncatedTo(ChronoUnit.SECONDS),
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
        betContent = betContent,
        challengeDate = challengeDate,
        deadline = deadline,
        // 나노초 자릿수가 섞이지 않도록 초 단위 절삭 (api-contract 확정).
        createdAt = createdAt.truncatedTo(ChronoUnit.SECONDS),
        // 받은 도전장은 opponent_id = :me 로만 조회되므로 challenger 가 곧 내 시점의 상대다.
        opponent = ReceivedChallengeOpponentDto(
            id = challengerId,
            nickname = challengerNickname,
            profileImageUrl = challengerProfileImageUrl,
            mission = challengerMission,
        ),
    )

    companion object {
        // @NotNull 이 먼저 걸리므로 정상 경로에서는 도달하지 않지만, 방어적으로 같은 문구를 쓴다.
        private const val MSG_OPPONENT_REQUIRED = "상대를 찾을 수 없어요"
        private const val MSG_DEADLINE_REQUIRED = "마감을 선택해주세요"
        private const val MSG_SIGNATURE_REQUIRED = "서명을 해주세요"
    }
}
