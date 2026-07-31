package com.lwg.challenge.controller.challenge.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.core.challenge.DeadlineType
import com.lwg.challenge.domain.challenge.ChallengeStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

/**
 * challenge-create 의 요청/응답 DTO 모음 (api-contract §1~5, status: confirmed).
 *
 * ## 직렬화 규약 — 함부로 바꾸지 말 것
 * - `LocalDate` → `@JsonFormat(shape = STRING, pattern = "yyyy-MM-dd")` 로 **평문 ISO date** 고정.
 *   모바일이 kotlinx-datetime 을 안 쓰고 `String` 으로 받기 때문에 `[2026,7,28]` 배열이 나가면 즉시 깨진다.
 *   Jackson 설정에 의존하지 않도록 어노테이션을 명시했고, 슬라이스 테스트가 실제 JSON 문자열을 assert 한다.
 * - `Instant` → `@JsonFormat(shape = STRING)` 로 `"2026-07-28T15:00:00Z"`. `Z` suffix 고정, 오프셋 표기 금지.
 *   모바일 매퍼는 파싱 실패 시 `Instant.DISTANT_PAST` 로 폴백해 **조용히** "마감된 카드" 가 되므로
 *   표기가 흔들리면 런타임에 드러나지 않는다.
 * - `challengerProfileImageUrl` 외의 필드는 절대 null / 누락으로 내보내지 않는다.
 *   모바일 DTO 가 전 필드에 기본값을 두는 방어적 패턴이라 누락이 빈 카드로 조용히 삼켜진다.
 */

// ─── 1. POST /api/v1/challenges ──────────────────────────────────

@Schema(description = "챌린지 신청 요청")
data class CreateChallengeBody(
    @field:NotNull(message = "상대를 찾을 수 없어요")
    @field:Positive(message = "상대를 찾을 수 없어요")
    @field:Schema(example = "42", description = "대결 상대 user id. 본인 불가 + ACCEPTED 친구 관계 필수")
    val opponentId: Long?,

    // trim 후 1자 이상은 service 가 검증한다 (공백만 입력한 경우 @Size 로는 못 잡는다).
    @field:Size(max = 100, message = "미션은 1자 이상 100자 이하로 입력해주세요")
    @field:Schema(example = "오늘 운동 1시간 하기", description = "챌린저 본인의 미션. trim 후 1~100자")
    val myMission: String?,

    @field:Size(max = 100, message = "내기 내용은 1자 이상 100자 이하로 입력해주세요")
    @field:Schema(example = "커피 사기", description = "내기 내용. trim 후 1~100자")
    val betContent: String?,

    @field:NotNull(message = "마감을 선택해주세요")
    @field:Schema(example = "TODAY", description = "TODAY = 오늘 자정 마감, TOMORROW = 내일 자정 마감 (KST 기준)")
    val deadlineType: DeadlineType?,
)

@Schema(description = "챌린지 신청 응답")
data class ChallengeCreateResponse(
    val data: ChallengeCreateData,
) : BaseResponse()

@Schema(description = "생성된 챌린지 요약")
data class ChallengeCreateData(
    @field:Schema(example = "7")
    val challengeId: Long,
    @field:Schema(example = "PENDING", description = "생성 직후는 항상 PENDING")
    val status: ChallengeStatus,
    @field:Schema(example = "2026-07-28", description = "KST 기준 날짜. 평문 ISO date 문자열")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val challengeDate: LocalDate,
    @field:Schema(example = "2026-07-28T15:00:00Z", description = "ISO-8601 UTC. KST 자정을 UTC 로 환산한 값")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val deadline: Instant,
)

// ─── 2. GET /api/v1/challenges/received ──────────────────────────

@Schema(description = "받은 도전장 목록 응답")
data class ReceivedChallengesResponse(
    val data: ReceivedChallengesData,
) : BaseResponse()

@Schema(description = "받은 도전장 리스트. 없으면 빈 배열")
data class ReceivedChallengesData(
    val challenges: List<ReceivedChallengeItem>,
)

@Schema(description = "받은 도전장 1건 (PENDING + 마감 미경과)")
data class ReceivedChallengeItem(
    @field:Schema(example = "7")
    val challengeId: Long,
    @field:Schema(example = "42")
    val challengerId: Long,
    @field:Schema(example = "민수")
    val challengerNickname: String,
    /** **nullable** — 카카오 프로필 미설정 가능. 본 응답에서 유일하게 null 이 될 수 있는 필드. */
    @field:Schema(example = "https://cdn/u/42.jpg", nullable = true)
    val challengerProfileImageUrl: String?,
    @field:Schema(example = "오늘 운동 1시간 하기")
    val challengerMission: String,
    @field:Schema(example = "커피 사기")
    val betContent: String,
    @field:Schema(example = "2026-07-28")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val challengeDate: LocalDate,
    @field:Schema(example = "2026-07-28T15:00:00Z", description = "ISO-8601 UTC")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val deadline: Instant,
    @field:Schema(example = "2026-07-28T02:11:00Z", description = "ISO-8601 UTC. 초 단위로 절삭")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val createdAt: Instant,
)

// ─── 3. POST /api/v1/challenges/{id}/accept ──────────────────────

@Schema(description = "챌린지 수락 요청")
data class AcceptChallengeBody(
    @field:Size(max = 100, message = "미션은 1자 이상 100자 이하로 입력해주세요")
    @field:Schema(example = "책 30페이지 읽기", description = "수락자 본인의 미션. opponent_mission 에 저장된다")
    val myMission: String?,
)

@Schema(description = "챌린지 수락 응답")
data class AcceptChallengeResponse(
    val data: ChallengeStatusData,
) : BaseResponse()

// ─── 4. POST /api/v1/challenges/{id}/reject ──────────────────────

@Schema(description = "챌린지 거절 응답")
data class RejectChallengeResponse(
    val data: ChallengeStatusData,
) : BaseResponse()

/** accept(`IN_PROGRESS`) / reject(`REJECTED`) 공통 응답 데이터. */
@Schema(description = "전이된 챌린지 상태")
data class ChallengeStatusData(
    @field:Schema(example = "7")
    val challengeId: Long,
    @field:Schema(example = "IN_PROGRESS")
    val status: ChallengeStatus,
)

// ─── 5. DELETE /api/v1/challenges/{id} ───────────────────────────

/**
 * 취소 응답. 삭제된 row 의 id 를 돌려준다 — 모바일이 어떤 카드를 지울지 대조하는 데 쓴다.
 *
 * friends 의 `CancelFriendRequestResponse(data.requestId)` 와 같은 모양 (프로젝트 일관성).
 * 204 No Content 는 쓰지 않는다 (프로젝트 규약).
 */
@Schema(description = "챌린지 취소 응답")
data class CancelChallengeResponse(
    val data: CancelChallengeData,
) : BaseResponse()

@Schema(description = "취소된 챌린지 id")
data class CancelChallengeData(
    @field:Schema(example = "7")
    val challengeId: Long,
)
