package com.lwg.challenge.controller.challenge.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.controller.common.WIRE_DATETIME
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.Outcome
import com.lwg.challenge.domain.challenge.RelativeParty
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * `GET /api/v1/challenges/{id}` 응답 (soul-oath).
 *
 * **요청자 기준(`me`/`opponent`)으로 뒤집어 준다.** 화면 문구가 전부 "나의 미션", "상대 서명"
 * 식이라 역할 기준으로 주면 앱이 `/users/me` 로 자기 역할을 알아낸 뒤 다시 뒤집어야 했다.
 * 홈 카드(`/challenges/active`)·받은 도전장과 같은 시점이다.
 *
 * 🔴 수락 전 맹세 화면(oath)도 이 응답을 쓴다. 그 화면을 여는 사람은 항상 받은 사람이라
 *    신청자가 `opponent` 로 나간다.
 */
@Schema(description = "챌린지 상세 + 계약서 + 양측 서명")
data class ChallengeDetailResponse(
    val data: ChallengeDetailData,
) : BaseResponse()

@Schema(description = "챌린지 상세")
data class ChallengeDetailData(
    @field:Schema(example = "7")
    val challengeId: Long,
    @field:Schema(example = "IN_PROGRESS")
    val status: ChallengeStatus,
    /**
     * 내 시점 결과 — `WIN` / `LOSE` / `DRAW` / `BOTH_LOSE`. **`status == COMPLETED` 가 아니면 항상 null.**
     * 홈 카드(`/challenges/active`)의 `myResult` 와 같은 enum 이다.
     *
     * 🔴 null 이어도 **키는 항상 나간다** (`@JsonInclude(NON_NULL)` 미설정 — #24 선례).
     */
    @field:Schema(example = "WIN", nullable = true)
    val myResult: Outcome?,
    /**
     * 기권한 쪽 — `ME` / `OPPONENT`. **null = 기권 없이 끝났거나 아직 안 끝났다** (challenge-forfeit).
     *
     * 🔴 값이 있으면 [status] 는 반드시 `COMPLETED` 이고 [myResult] 는 기권자의 패배다.
     * 🔴 null 이어도 **키는 항상 나간다** (`@JsonInclude(NON_NULL)` 미설정 — #24 선례).
     */
    @field:Schema(example = "OPPONENT", nullable = true, description = "기권한 쪽 (ME / OPPONENT). null = 기권 없음")
    val forfeitedBy: RelativeParty?,
    @field:Schema(example = "2026-08-03")
    val challengeDate: String,
    @field:Schema(example = "2026-08-04 00:00:00", description = "KST 기준")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val deadline: LocalDateTime,
    @field:Schema(example = "커피 사기")
    val betContent: String,
    val me: MyPartyDto,
    val opponent: PartyDto,
    /** 계약서. 아직 없으면 null. */
    val contract: ContractDto?,
)

/** 요청자 본인. 앱이 쓰지 않으므로 `userId` 는 내리지 않는다. */
@Schema(description = "챌린지 당사자 중 나")
data class MyPartyDto(
    @field:Schema(example = "이우건")
    val nickname: String,
    @field:Schema(nullable = true)
    val profileImageUrl: String?,
    /** 내가 받은 사람이고 아직 수락 전이면 null (V5 이후 `opponent_mission` nullable). */
    @field:Schema(example = "운동 1시간", nullable = true)
    val mission: String?,
)

@Schema(description = "챌린지 당사자 중 상대")
data class PartyDto(
    @field:Schema(example = "42")
    val userId: Long,
    @field:Schema(example = "민수")
    val nickname: String,
    @field:Schema(nullable = true)
    val profileImageUrl: String?,
    /** 상대가 아직 수락 전이면 null (V5 이후 `opponent_mission` nullable). */
    @field:Schema(example = "운동 1시간", nullable = true)
    val mission: String?,
)

@Schema(description = "영혼의 맹세 계약서")
data class ContractDto(
    val content: String,
    @field:Schema(description = "양측 서명 완료 여부. true 인 시점에만 챌린지가 IN_PROGRESS 다")
    val isFinalized: Boolean,
    /**
     * 서명 벡터 스트로크 JSON **문자열**. **null = 미서명.**
     *
     * ## 🔴 `@JsonRawValue` 를 쓰지 않는다 — 요청과 대칭이어야 한다
     *
     * raw 로 내보내면(= JSON 객체로) 모바일은 `JsonElement` 를 받아 **다시 문자열로 인코딩한 뒤**
     * 자기 코덱에 넘겨야 한다. **그 재인코딩이 키 순서·공백을 바꿀 수 있고, 그게 정확히 §4.2 의
     * "무손실" 이 깨지는 지점이다.** 요청도 문자열이므로(§4.4) 응답도 문자열이라야
     * **DB 에 들어간 바이트가 그대로 돌아온다.**
     *
     * ⚠️ [mySignedAt] 과 항상 **함께** 채워진다. 한쪽만 있는 조합은 서버가 만들지 않으며,
     * 나타났다면 저장이 깨진 것이다 (모바일이 그 조합을 "불러오지 못함" 으로 그린다).
     */
    @field:Schema(example = "{\"v\":1,\"g\":1000,\"s\":[[123,456,130,460]]}")
    val mySignature: String?,
    @field:Schema(example = "2026-08-03 11:20:00")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val mySignedAt: LocalDateTime?,
    @field:Schema(example = "{\"v\":1,\"g\":1000,\"s\":[[10,20,30,40]]}")
    val opponentSignature: String?,
    @field:Schema(example = "2026-08-03 11:25:00")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val opponentSignedAt: LocalDateTime?,
)
