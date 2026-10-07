package com.lwg.challenge.controller.challenge.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.core.challenge.DeadlineType
import com.lwg.challenge.domain.challenge.ChallengeResult
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.Outcome
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import com.lwg.challenge.controller.common.WIRE_DATE
import com.lwg.challenge.controller.common.WIRE_DATETIME
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * challenge-create 의 요청/응답 DTO 모음 (api-contract §1~5, status: confirmed).
 *
 * ## 직렬화 규약 — 함부로 바꾸지 말 것
 * - `LocalDate` → `@JsonFormat(shape = STRING, pattern = "yyyy-MM-dd")` 로 **평문 ISO date** 고정.
 *   모바일이 kotlinx-datetime 을 안 쓰고 `String` 으로 받기 때문에 `[2026,7,28]` 배열이 나가면 즉시 깨진다.
 *   Jackson 설정에 의존하지 않도록 어노테이션을 명시했고, 슬라이스 테스트가 실제 JSON 문자열을 assert 한다.
 * - `LocalDateTime` → `@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")` (ADR-0010). **`T` 도 `Z` 도 오프셋도 밀리초도 없다.**
 *   모바일은 이 패턴 전용 커스텀 파서를 쓰며 `T` 구분자가 오면 **실패한다**(실패하는 것 자체가 테스트로 고정돼 있다).
 *   즉 서버가 표기를 흘리면 조용히 통과하지 않는다.
 * - `opponent.profileImageUrl` 외의 필드는 절대 null / 누락으로 내보내지 않는다.
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

    /**
     * 영혼의 맹세 서명 — 벡터 스트로크 JSON **문자열** (soul-oath).
     *
     * ## 🔴 `JsonNode`(JSON 객체)가 아니라 `String` 인 이유
     *
     * 계약 §4.4의 검증 순서가 **"길이 → 파싱"** 이다. `JsonNode` 로 받으면 **DTO 바인딩 시점에
     * Jackson 이 이미 전체를 파싱**해서, 검증기가 길이를 먼저 보든 말든 파싱 비용은 이미 치러진 뒤다.
     * 즉 상한 검사가 악의적 대용량을 막지 못한다. **문자열로 받아야 그 순서가 실제로 성립한다.**
     *
     * 부수 효과로 **모바일이 인코딩한 바이트가 그대로 저장된다** — `JsonNode.toString()` 은
     * 재직렬화라 구조는 같아도 바이트가 같다는 보장이 없다(§4.2 "무손실").
     */
    @field:NotNull(message = "서명을 해주세요")
    @field:Schema(example = "{\"v\":1,\"g\":1000,\"s\":[[123,456,130,460]]}", description = "서명 벡터 스트로크 JSON 문자열")
    val signature: String?,
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
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATE)
    val challengeDate: LocalDate,
    @field:Schema(example = "2026-08-01 00:00:00", description = "KST 기준. TODAY 선택 시 challengeDate 익일 00:00")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val deadline: LocalDateTime,
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
    @field:Schema(example = "커피 사기")
    val betContent: String,
    @field:Schema(example = "2026-07-28")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATE)
    val challengeDate: LocalDate,
    @field:Schema(example = "2026-08-01 00:00:00", description = "KST 기준")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val deadline: LocalDateTime,
    @field:Schema(example = "2026-07-28 11:11:00", description = "KST 기준. 초 단위로 절삭")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val createdAt: LocalDateTime,
    val opponent: ReceivedChallengeOpponentDto,
)

/**
 * 도전장을 보낸 사람. 받은 도전장은 `opponent_id = :me` 로만 조회되므로 DB 상 challenger 가
 * 항상 **내 시점의 상대**다 — 홈 진행 중 목록의 `opponent` 와 같은 이름으로 맞춘다.
 *
 * [mission] 은 DB 상 `challenges.challenger_mission` 이지만 "상대가 스스로에게 건 미션" 이라
 * 화면에서 상대 정보와 한 덩어리로 읽혀 이 안에 둔다.
 */
@Schema(description = "도전장을 보낸 상대")
data class ReceivedChallengeOpponentDto(
    @field:Schema(example = "42")
    val id: Long,
    @field:Schema(example = "민수")
    val nickname: String,
    /** **nullable** — 카카오 프로필 미설정 가능. 본 응답에서 유일하게 null 이 될 수 있는 필드. */
    @field:Schema(example = "https://cdn/u/42.jpg", nullable = true)
    val profileImageUrl: String?,
    @field:Schema(example = "오늘 운동 1시간 하기")
    val mission: String,
)

// ─── 3. POST /api/v1/challenges/{id}/accept ──────────────────────

@Schema(description = "챌린지 수락 요청")
data class AcceptChallengeBody(
    @field:Size(max = 100, message = "미션은 1자 이상 100자 이하로 입력해주세요")
    @field:Schema(example = "책 30페이지 읽기", description = "수락자 본인의 미션. opponent_mission 에 저장된다")
    val myMission: String?,

    /**
     * 영혼의 맹세 서명 JSON **문자열**. 이 요청 하나로 수락 + 서명 + IN_PROGRESS 전환이
     * **원자적으로** 일어난다. 타입이 `String` 인 이유는 [CreateChallengeBody.signature] 참조.
     */
    @field:NotNull(message = "서명을 해주세요")
    @field:Schema(example = "{\"v\":1,\"g\":1000,\"s\":[[10,20,30,40]]}", description = "서명 벡터 스트로크 JSON 문자열")
    val signature: String?,
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

// ─── 6. POST /api/v1/challenges/{id}/forfeit ─────────────────────

/**
 * 기권 응답 (challenge-forfeit 계약 §1).
 *
 * 🔴 **요청 본문이 없다.** 기권자는 JWT 의 주체다 — `userId` 를 body 나 쿼리로 받으면
 * **남의 챌린지를 기권시킬 수 있는 문**이 열린다. 그래서 이 파일에 `ForfeitChallengeBody` 가
 * 없는 것이 의도다. 만들지 마라.
 */
@Schema(description = "기권 응답 — 챌린지가 즉시 종료되고 결과가 확정된다")
data class ForfeitChallengeResponse(
    val data: ForfeitChallengeData,
) : BaseResponse()

@Schema(description = "기권으로 확정된 결과")
data class ForfeitChallengeData(
    @field:Schema(example = "7")
    val challengeId: Long,

    /** 항상 `COMPLETED`. 기권은 자정 배치를 기다리지 않는다. */
    @field:Schema(example = "COMPLETED", description = "항상 COMPLETED — 기권은 즉시 확정이다")
    val status: ChallengeStatus,

    /**
     * 판정 결과. **역할 기준**이다 — 기권자가 challenger 면 `OPPONENT_WIN`, opponent 면
     * `CHALLENGER_WIN`. 상세(`GET /challenges/{id}`)의 `result` 와 같은 축이다.
     */
    @field:Schema(example = "OPPONENT_WIN", description = "역할 기준. 기권자의 반대편이 이긴다")
    val result: ChallengeResult,

    /**
     * **요청자(= 기권자) 시점.** 오늘 항상 `LOSE` 다.
     *
     * 🔴 상수처럼 보이지만 서버가 박아 넣는 값이 아니라 `ChallengeVerdict.outcomeFor` 를
     * 실제로 태운 결과다. 빼면 앱이 `result` 를 역할로 뒤집거나 *"기권했으니 졌다"* 를
     * 앱 안에 심어야 하고, 그게 `challenge-result` 가 `myResult` 를 신설하며 막았던 사본이다.
     */
    @field:Schema(example = "LOSE", description = "요청자 시점. 기권자가 부르는 API 라 항상 LOSE")
    val myResult: Outcome,

    /**
     * 확정 시각. **기권 시각이기도 하다** — 기권은 즉시 `COMPLETED` 라 별도 컬럼이 없다(V12).
     */
    @field:Schema(example = "2026-09-05 14:23:11", description = "KST 기준. 기권 시각과 같다")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val completedAt: LocalDateTime,
)
