package com.lwg.challenge.controller.challenge.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.controller.common.WIRE_DATETIME
import com.lwg.challenge.domain.verification.VerificationStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 챌린지 인증 응답 DTO (challenge-verification api-contract §3~4).
 *
 * 🔴 **업로드는 multipart part `photo` 로 들어오며 요청 DTO 가 없다** (ADR-0011 §3 개정 —
 * 서버가 바이트를 받아 저장까지 한 번에 처리한다). 사진 바이트 서빙만 `BaseResponse` 가
 * 아닌 **bare HTTP status** 로 나간다 — `VerificationController.readPhoto` 참조.
 */

// ─── §3 제출 ─────────────────────────────────────────────────────

@Schema(description = "제출 결과")
data class SubmitVerificationResponse(
    val data: SubmitVerificationData,
) : BaseResponse()

@Schema(description = "확정된 내 인증")
data class SubmitVerificationData(
    @field:Schema(example = "7")
    val challengeId: Long,
    @field:Schema(example = "VERIFIED")
    val status: VerificationStatus,
    @field:Schema(example = "2026-08-14 15:32:10", description = "KST 기준")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val verifiedAt: LocalDateTime?,
)

// ─── §4 조회 ─────────────────────────────────────────────────────

@Schema(description = "양측 인증 상태 + 사진 경로")
data class ChallengeVerificationsResponse(
    val data: ChallengeVerificationsData,
) : BaseResponse()

/**
 * `challenger`/`opponent` 를 **역할 그대로** 준다 — `GET /challenges/{id}` 와 같은 시점이라
 * 두 응답을 상세 화면에서 합칠 때 뒤집기 계산이 없다 (soul-oath api-contract §3 과 같은 판단).
 */
@Schema(description = "챌린지 인증 현황")
data class ChallengeVerificationsData(
    @field:Schema(example = "7")
    val challengeId: Long,
    val challenger: PartyVerificationDto,
    val opponent: PartyVerificationDto,
)

@Schema(description = "한 사람의 인증 상태")
data class PartyVerificationDto(
    @field:Schema(example = "1")
    val userId: Long,

    @field:Schema(example = "VERIFIED", description = "PENDING / VERIFIED / FAILED. row 가 없으면 PENDING")
    val status: VerificationStatus,

    /**
     * **만료 없는 고정 상대 경로.** `VERIFIED` 가 아니면 null.
     *
     * 🔴 이 경로를 열 때는 **`Authorization` 이 필요하다** (예전 서명 URL 과 반대다 —
     * 2026-08-18 2차 결정으로 조회 서명 URL 을 폐기했다). 당사자 검사는 서버가
     * **요청 시점에 JWT 로** 한다 (ADR-0011 §4).
     *
     * 🔴 **상대 경로**라 앱이 자기 baseUrl 에 붙여 쓴다 — 서버는 앱이 자신을 부르는 host
     * (에뮬레이터 `10.0.2.2` / 시뮬레이터 `localhost`)를 알 필요가 없다.
     */
    @field:Schema(nullable = true, example = "/api/v1/challenges/7/photos/challenger")
    val photoUrl: String?,

    @field:Schema(nullable = true, example = "2026-08-14 15:32:10", description = "KST 기준")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATETIME)
    val verifiedAt: LocalDateTime?,

    /**
     * 사진이 **탈퇴로 삭제됐는가.** non-null 이며 기본값은 `false` — 정상 사용자의 카드에서는
     * 항상 `false` 다. 기존 필드의 의미는 하나도 바뀌지 않는다 (**순수 추가**).
     *
     * 판정식은 `status == VERIFIED && 사진 key 없음 && 그 party 의 계정이 DELETED` 다
     * (`VerificationService.view` KDoc 에 세 갈래 표가 있다).
     *
     * ## 🔴 왜 필요한가 — `photoUrl == null` 이 이미 다른 뜻으로 쓰이고 있다
     *
     * 앱은 `photoUrl == null` 을 `"사진을 불러오지 못했어요"` 로 그린다. 그 자리는
     * *"인증은 됐는데 URL 이 없는 비정상"* 이라는 뜻이다. 탈퇴자의 삭제된 사진이 그 문구를
     * 빌려 쓰면 (1) **영구 상태를 일시적 실패의 어휘로** 말하게 되고, (2) 더 나쁘게는
     * **진짜 데이터 이상이 탈퇴자 뒤에 숨는다.** 그래서 신호를 하나 더 준다.
     *
     * ## 🔴 `isWithdrawn` 이 아닌 것이 의도다
     *
     * 앱이 *"왜"* 가 아니라 *"뭘 그릴지"* 만 받게 한다. design 이 보관함 카드에서 `isWithdrawn`
     * 플래그를 **명시적으로 거부**했다 — 받으면 특별 표시를 넣게 되고, 증거 보존 화면에서
     * 그건 *"이 기록은 무효"* 로 읽힌다. `photoDeleted` 는 **사진 자리 하나만 바꾸고 다른 데
     * 쓸 수 없다.**
     */
    @field:Schema(
        example = "false",
        description = "true = 탈퇴로 사진이 삭제됨(영구). false 인데 photoUrl 도 null 이면 그건 로드 실패/미인증이다",
    )
    val photoDeleted: Boolean,
)
