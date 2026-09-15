package com.lwg.challenge.controller.challenge.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.controller.common.WIRE_DATE
import com.lwg.challenge.controller.common.WIRE_MONTH
import com.lwg.challenge.domain.challenge.Outcome
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import java.time.YearMonth

/**
 * GET /api/v1/challenges/history 응답 — 계약서 보관함 **한 달치**
 * (mypage T-B1, 2026-08-28 월 단위 개정).
 *
 * - `error=false`, `code=200`, `data` 에 조회한 월 · 이동 가능 범위 · 그 달의 카드 목록.
 * - 토큰 만료 / 인프라 에러는 `GlobalExceptionHandler` / `UnauthorizedEntryPoint` 가 BaseResponse 직접 반환.
 *
 * 🔴 **1차의 "전체 반환 + 앱이 `challengeDate` 앞 7글자로 월 그룹핑" 은 폐기됐다.**
 * 응답 하나가 이미 한 달치라 앱이 자를 것이 없다.
 */
@Schema(description = "계약서 보관함 응답 (BaseResponse 상속)")
data class ChallengeHistoryResponse(
    val data: ChallengeHistoryMonthData,
) : BaseResponse()

@Schema(description = "보관함 한 달치 — 조회한 월 + 과거 하한 + 카드 목록")
data class ChallengeHistoryMonthData(
    /**
     * 실제로 조회한 월. 요청에 `month` 가 없었으면 **서버가 정한 이번 달(KST)** 이다.
     *
     * 앱은 두 가지로 쓴다:
     * 1. 🔴 **상한.** 파라미터 없이 부른 첫 응답의 이 값이 곧 *"이번 달"* 이다 —
     *    **앱은 기기 시계를 읽지 않는다.**
     * 2. **응답 경합 방어.** 화살표 연타가 정상 사용이라 요청 겹침도 정상이고, 늦게 도착한 옛
     *    응답이 현재 월 표시 아래 다른 달 카드를 그리는 것을 이 값으로 막는다.
     */
    @field:Schema(example = "2026-08", description = "KST 기준 월. `yyyy-MM` 평문 문자열")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_MONTH)
    val month: YearMonth,


    /**
     * 이 사용자의 **가장 오래된 `COMPLETED` 기록의 월.** 🔴 **nullable** — 종료 기록 0건이면 null.
     *
     * 🔴 **용도는 빈 상태 2종 판별 하나뿐이다** — `null` 이면 *"보관함이 통째로 비었다"*(기록 전무
     * 화면 + *"챌린지 만들기"* CTA), non-null 이면 *"이 달만 비었다"*(빈 달 화면, CTA 없음).
     * `histories: []` 만으로는 그 둘이 안 갈리고 **다른 출처가 없다.** 사용자 전역 값이다.
     *
     * ## 🔴 이 값으로 `<` 를 막지 마라 (2026-08-28 2차 개정)
     *
     * v3 까지는 이게 **하한**이었다. 사용자가 그 규칙을 폐기했다 — 기록이 전부 이번 달이면
     * **하한 = 상한**이 되어 양쪽 화살표가 죽고 **"고장"으로 읽힌다.**
     * 🔴 **이제 하한 자체가 없다 — `<` 는 항상 활성이고 과거는 어느 달이든 빈 달로 열린다.**
     *
     * ⚠️ 월 값이 그대로 남아 있어 **다시 하한으로 쓰기 쉽다 — 그게 방금 폐기한 규칙이다.**
     * 필드를 지우지 않는 건 위 판별 용도가 살아 있고 대체 출처가 없어서지 경계로 쓰라는 뜻이 아니다.
     * design 은 이 값을 빈 달 부제(*"첫 기록은 YYYY년 M월이에요"*)의 기준으로 쓴다 —
     * **차단이 아니라 고지**이며, 그게 1차 하한 논거가 요구한 *"주장할 수단"* 이다.
     *
     * ⚠️ 이 프로젝트 Jackson 에는 `@JsonInclude(NON_NULL)` 이 없어 **null 이어도 키는 항상
     * 실린다**(`WireShapeContractTest` KDoc 실측). 모바일이 키 존재를 전제해도 된다.
     */
    @field:Schema(
        example = "2026-08",
        nullable = true,
        description = "가장 오래된 COMPLETED 기록의 월(`yyyy-MM`). 종료 기록이 없으면 null. " +
            "**빈 상태 2종 판별 전용** — 경계로 쓰지 않는다",
    )
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_MONTH)
    val firstArchivedMonth: YearMonth?,

    @field:Schema(
        description = "그 달의 종료(COMPLETED) 챌린지를 `challengeDate` 내림차순(같은 날은 id 내림차순)으로 담는다. " +
            "**빌 수 있다** — 빈 달을 건너뛰지 않는 것이 확정된 동작이라 `[]` 는 정상 응답이다. " +
            "빈 배열 자체로는 '이 달만 비었다'와 '기록이 하나도 없다'가 구분되지 않는다 — " +
            "`firstArchivedMonth == null` 인 것이 후자다.",
    )
    val histories: List<ChallengeHistoryDto>,
)

@Schema(description = "보관함 카드 1건. 판정이 끝난 챌린지만 담긴다.")
data class ChallengeHistoryDto(
    @field:Schema(example = "1001")
    val challengeId: Long,

    /**
     * 챌린지 날짜. **조회 범위 키이자 정렬 키**다.
     *
     * 🔴 **판정 시각(`completedAt`)이 아니다.** 판정이 자정 직후 배치라 7월 31일 챌린지의
     * 판정 시각은 8월 1일 00:05 다 — 그걸로 범위를 자르면 **7월 마지막 날 챌린지가 8월
     * 응답에 들어가서**, 카드에 적힌 날짜(7/31)와 화면 상단의 월 표시(8월)가 한 화면에서
     * 다른 말을 한다. 홈 목록이 판정 시각 순인 것은 목적이 다르기 때문이다
     * (`ChallengeRepository.findCompletedHistoryByUserInMonth` KDoc).
     *
     * ⚠️ 1차에서는 *"앱이 앞 7글자로 자르는 월 그룹 키"* 이기도 했다. 월 단위 조회가 되면서
     * 그 용도는 사라졌다 — 응답 하나가 이미 한 달치다.
     */
    @field:Schema(example = "2026-08-24", description = "KST 기준 날짜. 평문 ISO date 문자열")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = WIRE_DATE)
    val challengeDate: LocalDate,

    /**
     * 상대 닉네임. **탈퇴자도 non-null 이다** — 탈퇴는 row 를 남긴 채 닉네임을
     * `"탈퇴한 사용자"` 로 익명화한다 (mypage T-B2).
     *
     * 🔴 그래서 이 응답에 **탈퇴 여부 플래그가 없다.** design 이 보관함 카드의 `isWithdrawn` 을
     * 명시적으로 거부했다 — 받으면 앱이 특별 표시를 넣게 되고, 증거 보존 화면에서 그건
     * *"이 기록은 무효"* 로 읽힌다.
     */
    @field:Schema(example = "민수")
    val opponentNickname: String,

    @field:Schema(example = "오늘 운동 1시간 하기")
    val myMission: String,

    @field:Schema(example = "책 30페이지 읽기")
    val opponentMission: String,

    @field:Schema(example = "커피 사기")
    val bet: String,

    /**
     * 내 시점 결과 — `WIN` / `LOSE` / `DRAW` / `BOTH_LOSE`. **non-null 이다.**
     *
     * 🔴 `COMPLETED` 만 담기는 목록이라 판정이 이미 끝나 있다. `/challenges/active` 의
     * `myResult` 가 nullable 인 것과 대비 지점인데, 그쪽은 진행 중 카드를 같은 목록에 섞기
     * 때문이다.
     *
     * 🔴 **역할 기준 `result` 원본은 이 응답에 없다** (design §7-④ 확정). 카드가 그리는 것은
     * *"내가 이겼나"* 하나이고, 두 축이 다 내려가면 **앱이 어느 쪽을 믿을지 고르게 되며 그
     * 선택이 화면마다 갈린다.** `BOTH_LOSE` 가 `Outcome` 에 이미 있어 "양쪽 다 졌다" 도
     * 이 필드 하나로 표현된다 (`Outcome` KDoc).
     *
     * 🔴 **인증 상태 2필드도 없다.** 카드가 인증 pill 을 그리지 않기 때문이다.
     *
     * ⚠️ **원래 근거는 이것이 아니었고, 그 근거는 이제 거짓이다** (challenge-forfeit, 2026-09-05).
     * 원문은 *"결과 4개가 양측 인증 상태를 **유일하게 결정**하므로(승 = 나만 인증 / 패 = 상대만 /
     * 무 = 양쪽 / 양패 = 양쪽 미인증) 이 값 하나가 인증 pill 두 개의 정보를 전부 담는다"* 였다.
     * **기권이 그 대응을 일대일이 아니게 만들었다** — 기권자는 인증 여부와 무관하게 지므로
     * `LOSE` 가 *"상대만 인증"* 일 수도, *"내가 기권했고 아무도 인증 안 함"* 일 수도 있다.
     *
     * 🔑 **결론(필드를 안 담는다)은 그대로다. 근거만 바뀐다.** 🔴 **언젠가 카드에 인증 pill 을
     * 그리게 되면 이 값으로 역산하지 말고 verifications 를 조회해라** — 역산은 이제 틀린 답을 낸다.
     */
    @field:Schema(example = "WIN")
    val myResult: Outcome,
)
