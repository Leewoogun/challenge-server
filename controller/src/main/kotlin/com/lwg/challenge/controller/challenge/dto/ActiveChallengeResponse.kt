package com.lwg.challenge.controller.challenge.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.Outcome
import com.lwg.challenge.domain.verification.VerificationStatus
import io.swagger.v3.oas.annotations.media.Schema
import com.lwg.challenge.controller.common.WIRE_DATETIME
import java.time.LocalDateTime

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
    /**
     * 카드의 상태. **이 응답은 `IN_PROGRESS` 와 최근 `COMPLETED` 만 담는다** (계약 §2.3).
     *
     * 🔴 COMPLETED 가 섞이기 전엔 **필드 자체가 없었다** — 전부 진행 중이라 구분할 필요가 없었다.
     *    카드가 결과 표시로 갈아탈 유일한 신호라 함께 추가한다.
     */
    @field:Schema(example = "IN_PROGRESS")
    val status: ChallengeStatus,
    /**
     * 내 시점 결과 — `WIN` / `LOSE` / `DRAW` / `BOTH_LOSE`. `IN_PROGRESS` 면 null (키는 항상 존재).
     *
     * 🔴 [me]·[opponent] 와 같은 **"내 시점" 축**이다. 역할 기준 판정을 앱이 뒤집으려면
     *    *내가 challenger 인지* 알아야 하는데 **이 응답에는 그 정보가 없다**. 그래서 서버가 뒤집어 준다.
     *    역할 기준 `result` 는 같은 이유로 해석할 수 없어 내려주지 않는다.
     * 🔴 판정은 한 사람의 속성이 아니라 챌린지의 결과라 `me` 안이 아닌 최상위에 둔다.
     * 🔴 `BOTH_LOSE` 는 `LOSE` 로 접지 않고 **그대로 나간다** (모바일 협의 확정). 접으면 앱이
     *    "둘 다 못 했다" 를 인증 상태 두 개로 역산해야 하고, 그건 판정 규칙의 두 번째 사본이
     *    앱에 생기는 것이다. 관점 중립인 값이라 "내 시점" 축은 깨지지 않는다
     *    (`Outcome` KDoc).
     */
    @field:Schema(example = "LOSE", nullable = true)
    val myResult: Outcome?,
    /** KST 기준 (예: `"2026-05-26 00:00:00"`). 모바일에서 상대 시간 변환. ADR-0010. */
    @field:Schema(example = "2026-05-26 00:00:00", description = "KST 기준 yyyy-MM-dd HH:mm:ss")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val deadline: LocalDateTime,
    @field:Schema(example = "커피 사기")
    val bet: String,
    val me: ActiveChallengeMeDto,
    val opponent: ActiveChallengeOpponentDto,
)

/** 카드의 "나" 칸. 홈 카드는 내 닉네임을 쓰지 않아 nickname 은 없다. */
@Schema(description = "홈 카드의 내 쪽 값")
data class ActiveChallengeMeDto(
    @field:Schema(example = "오늘 운동 1시간 하기")
    val mission: String,
    @field:Schema(example = "PENDING")
    val verificationStatus: VerificationStatus,
)

@Schema(description = "홈 카드의 상대 쪽 값")
data class ActiveChallengeOpponentDto(
    @field:Schema(example = "민수")
    val nickname: String,
    @field:Schema(example = "책 30페이지 읽기")
    val mission: String,
    @field:Schema(example = "VERIFIED")
    val verificationStatus: VerificationStatus,
)
