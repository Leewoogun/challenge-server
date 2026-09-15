package com.lwg.challenge.controller.challenge.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.controller.common.WIRE_DATETIME
import com.lwg.challenge.domain.challenge.ChallengeResult
import com.lwg.challenge.domain.challenge.ChallengeStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * `GET /api/v1/challenges/{id}` 응답 (soul-oath).
 *
 * **`challenger`/`opponent` 를 역할 그대로 준다 — "나/상대" 로 뒤집지 않는다.**
 * 상세는 계약서를 보여주는 화면이라 양쪽을 다 그리며, 뒤집으면 "이 서명이 누구 것인지" 를
 * 클라가 다시 계산해야 한다 (api-contract §3).
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
     * 판정 결과. **`status == COMPLETED` 가 아니면 항상 null** (도메인 불변식 — Challenge KDoc).
     *
     * 🔴 역할 기준이다 — `CHALLENGER_WIN` 을 "내가 이겼다" 로 뒤집는 것은 앱의 몫이다.
     *    상세는 계약서를 보여주는 화면이라 `challenger`/`opponent` 를 역할 그대로 주고 있고
     *    (soul-oath §3), result 만 시점을 달리하면 같은 응답 안에 두 기준이 섞인다.
     *    홈 카드(`/challenges/active`)는 성격이 달라 `myResult` 를 따로 준다.
     *
     * 🔴 null 이어도 **키는 항상 나간다** (`@JsonInclude(NON_NULL)` 미설정 — #24 선례).
     */
    @field:Schema(example = "CHALLENGER_WIN", nullable = true)
    val result: ChallengeResult?,
    /**
     * 기권한 사람의 `userId`. **null = 기권 없이 끝났거나 아직 안 끝났다** (challenge-forfeit).
     *
     * ## 🔴 `Boolean` 이 아니라 userId 인 이유
     *
     * `forfeited: Boolean` 이면 앱은 *"누가 기권했나"* 를 알기 위해
     * `forfeited && result == OPPONENT_WIN → challenger 가 기권` 으로 **역산**해야 한다.
     * 그건 판정 규칙의 사본을 앱에 심는 것이고, [result] 옆에 `myResult` 를 따로 두지 않은
     * 이 화면의 판단(양쪽을 역할 그대로 그린다)과도 어긋난다.
     * userId 를 주면 앱은 [challenger] / [opponent] 의 `userId` 와 **비교만** 하면 된다.
     *
     * 🔴 값이 있으면 [status] 는 반드시 `COMPLETED` 이고 [result] 는 기권자의 **반대편 승리**다.
     * 🔴 null 이어도 **키는 항상 나간다** (`@JsonInclude(NON_NULL)` 미설정 — #24 선례).
     */
    @field:Schema(example = "42", nullable = true, description = "기권한 사람의 userId. null = 기권 없음")
    val forfeitedBy: Long?,
    @field:Schema(example = "2026-08-03")
    val challengeDate: String,
    @field:Schema(example = "2026-08-04 00:00:00", description = "KST 기준")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val deadline: LocalDateTime,
    @field:Schema(example = "커피 사기")
    val betContent: String,
    val challenger: PartyDto,
    val opponent: PartyDto,
    /** 계약서. 아직 없으면 null. */
    val contract: ContractDto?,
)

@Schema(description = "챌린지 당사자 한 명")
data class PartyDto(
    @field:Schema(example = "1")
    val userId: Long,
    @field:Schema(example = "이우건")
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
     * ⚠️ [challengerSignedAt] 과 항상 **함께** 채워진다. 한쪽만 있는 조합은 서버가 만들지 않으며,
     * 나타났다면 저장이 깨진 것이다 (모바일이 그 조합을 "불러오지 못함" 으로 그린다).
     */
    @field:Schema(example = "{\"v\":1,\"g\":1000,\"s\":[[123,456,130,460]]}")
    val challengerSignature: String?,
    @field:Schema(example = "2026-08-03 11:20:00")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val challengerSignedAt: LocalDateTime?,
    @field:Schema(example = "{\"v\":1,\"g\":1000,\"s\":[[10,20,30,40]]}")
    val opponentSignature: String?,
    @field:Schema(example = "2026-08-03 11:25:00")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val opponentSignedAt: LocalDateTime?,
)
