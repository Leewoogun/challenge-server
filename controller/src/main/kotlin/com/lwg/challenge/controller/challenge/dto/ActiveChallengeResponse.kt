package com.lwg.challenge.controller.challenge.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.domain.challenge.ChallengeResult
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
     * 판정 결과 원본. **역할 기준**(`CHALLENGER_WIN`)이라 "내가 이겼다" 가 아니다.
     * `IN_PROGRESS` 면 null 이고, **null 이어도 키는 항상 나간다** (#24 선례).
     *
     * 카드가 필요한 것은 대개 [myResult] 지만, 이 값은 역할까지 보존하므로 "누가 이겼나" 를
     * 그대로 그려야 하는 화면(상세·기록 등)이 본다.
     */
    @field:Schema(example = "OPPONENT_WIN", nullable = true)
    val result: ChallengeResult?,
    /**
     * 내 시점 결과 — `WIN` / `LOSE` / `DRAW` / `BOTH_LOSE`. `IN_PROGRESS` 면 null (키는 항상 존재).
     *
     * 🔴 이 응답의 나머지 필드([myMission])와 같은 **"내 시점" 축**이다. 역할 기준 [result] 를
     *    앱이 뒤집으려면 *내가 challenger 인지* 알아야 하는데 **이 응답에는 그 정보가 없다**
     *    (`opponentNickname` 만 있고 역할은 없다). 그래서 서버가 뒤집어 준다.
     * 🔴 `BOTH_LOSE` 는 `LOSE` 로 접지 않고 **그대로 나간다** (모바일 협의 확정). 접으면 앱이
     *    "둘 다 못 했다" 를 인증 상태 두 개로 역산해야 하고, 그건 판정 규칙의 두 번째 사본이
     *    앱에 생기는 것이다. 관점 중립인 값이라 "내 시점" 축은 깨지지 않는다
     *    (`Outcome` KDoc).
     */
    @field:Schema(example = "LOSE", nullable = true)
    val myResult: Outcome?,
    @field:Schema(example = "오늘 운동 1시간 하기")
    val myMission: String,
    @field:Schema(example = "민수")
    val opponentNickname: String,
    @field:Schema(example = "책 30페이지 읽기")
    val opponentMission: String,
    /** KST 기준 (예: `"2026-05-26 00:00:00"`). 모바일에서 상대 시간 변환. ADR-0010. */
    @field:Schema(example = "2026-05-26 00:00:00", description = "KST 기준 yyyy-MM-dd HH:mm:ss")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val deadline: LocalDateTime,
    @field:Schema(example = "PENDING")
    val myVerificationStatus: VerificationStatus,
    @field:Schema(example = "VERIFIED")
    val opponentVerificationStatus: VerificationStatus,
    @field:Schema(example = "커피 사기")
    val bet: String,
)
