package com.lwg.challenge.controller.challenge

import com.lwg.challenge.controller.challenge.dto.ChallengeHistoryDto
import com.lwg.challenge.controller.challenge.dto.ChallengeHistoryMonthData
import com.lwg.challenge.controller.challenge.dto.ChallengeHistoryResponse
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.service.challenge.ChallengeHistoryPage
import com.lwg.challenge.service.challenge.ChallengeHistoryService
import com.lwg.challenge.service.challenge.ChallengeHistoryView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth
import java.time.format.DateTimeParseException

/**
 * 계약서 보관함 조회 컨트롤러 — `GET /api/v1/challenges/history`
 * (mypage T-B1, 2026-08-28 **월 단위 개정**).
 *
 * 응답 하나가 **한 달치**다. 1차의 *"종료 챌린지 전체 + 앱이 월 그룹핑"* 은 폐기됐다 —
 * 사용자가 월 1칸씩 순차 이동(빈 달도 건너뛰지 않음, 첫 진입은 이번 달)으로 확정했다.
 *
 * ## 🔴 [ActiveChallengeController] 에 얹지 않았다
 *
 * 같은 `/api/v1/challenges` 네임스페이스지만 **응답 shape 도 정렬 축도 다르다** —
 * 홈은 `deadline`/`completed_at` 축의 진행형 목록이고 보관함은 `challengeDate` 축의 종료
 * 기록이다. 한 컨트롤러에 두면 두 계약이 한 파일에서 같이 흔들린다
 * (`ChallengeCommandController` 와 [ActiveChallengeController] 가 이미 갈라져 있는 이유와 같다).
 *
 * ## 경로 충돌은 새로운 위험이 아니다
 *
 * `ChallengeCommandController` 에 `@GetMapping("/challenges/{id}")` 가 있지만 Spring 은
 * **리터럴 경로를 변수 경로보다 우선**한다. 이 레포에 이미 선례가 둘 있다 —
 * `/challenges/active`([ActiveChallengeController])와 `/challenges/received` 가 같은 `{id}` 와
 * 공존하며 정상 동작 중이다. 그래도 `ChallengeHistoryControllerTest` 가 한 줄로 고정해 둔다:
 * `history` 가 `{id}` 로 새면 *"id=history"* 파싱 에러가 난다.
 *
 * 인증은 SecurityFilterChain `.anyRequest().authenticated()` + `JwtAuthenticationFilter` 가 담당.
 * 정상 케이스에서는 SecurityContext 의 principal 이 userId(Long).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Challenge", description = "챌린지 조회")
class ChallengeHistoryController(
    private val challengeHistoryService: ChallengeHistoryService,
) {

    @GetMapping("/challenges/history")
    @Operation(
        summary = "계약서 보관함 — 한 달치 조회",
        description = "현재 사용자가 challenger 또는 opponent 인 COMPLETED 챌린지 중 " +
            "`month` 에 해당하는 것을 `challengeDate` 내림차순(같은 날은 id 내림차순)으로 반환한다. " +
            "`month` 를 생략하면 **서버 KST 기준 이번 달**이다 — 앱의 첫 진입이 기기 시계 없이 " +
            "이번 달로 열리도록 한 것이며, 그 응답의 `month` 에코가 앱이 쓰는 **상한**이 된다. " +
            "🔴 **과거 하한은 없다** — `<` 는 항상 활성이고 어느 과거 달을 요청해도 빈 달로 " +
            "정상 응답한다(거부하는 것은 미래 달뿐). `firstArchivedMonth` 는 **경계가 아니라 " +
            "빈 상태 2종 판별용**이다(null = 기록 전무). " +
            "EXPIRED 는 계약서·서명·사진·결과가 전부 없으므로 제외된다. " +
            "빈 달은 `histories: []` 로 정상 응답한다 — 빈 달을 건너뛰지 않는 것이 확정된 동작이다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun getHistories(
        @Parameter(
            description = "조회할 월 (`yyyy-MM`). 생략하면 서버 KST 이번 달. " +
                "미래 달이거나 형식이 틀리면 HTTP 200 + code=700.",
            example = "2026-08",
        )
        @RequestParam(name = "month", required = false) month: String?,
    ): ChallengeHistoryResponse {
        val userId: Long = currentUserId()
        val page: ChallengeHistoryPage = challengeHistoryService.getHistories(userId, parseMonth(month))
        return ChallengeHistoryResponse(
            data = ChallengeHistoryMonthData(
                month = page.month,
                firstArchivedMonth = page.firstArchivedMonth,
                histories = page.histories.map { it.toDto() },
            ),
        )
    }

    // ─── helpers ─────────────────────────────────────────────────

    /**
     * `?month=2026-08` → [YearMonth]. 값이 없으면 null 을 그대로 넘긴다 (= 서비스가 이번 달로 채운다).
     *
     * ## 🔴 `YearMonth` 를 파라미터 타입으로 선언하지 않은 이유
     *
     * `@RequestParam month: YearMonth?` + `@DateTimeFormat` 로도 바인딩은 되지만, 형식이 틀리면
     * Spring 이 `MethodArgumentTypeMismatchException` 을 던진다. `GlobalExceptionHandler` 에는
     * 그 타입의 핸들러가 **없어서** `handleUncaught` 로 떨어지고 **HTTP 500 + code=500** 이
     * 나간다 — 클라이언트가 잘못 보낸 쿼리 파라미터 하나가 서버 장애로 보고되는 것이다
     * (ADR-0002: 5xx 는 인프라 장애 전용).
     *
     * 전역 핸들러를 새로 추가하면 **다른 모든 엔드포인트의 타입 불일치 응답까지 바뀐다** —
     * 이 개정이 열 범위가 아니다. 그래서 문자열로 받아 여기서 파싱하고
     * [SnackbarException] 으로 던진다. 비즈니스 에러 경로(HTTP 200 + code=700)에 그대로 얹힌다.
     *
     * ⚠️ `YearMonth.parse` 는 ISO(`yyyy-MM`)를 요구하므로 `2026-8` 같은 값은 여기서 걸린다.
     * 앱이 월을 만들어 보낼 때 zero-padding 이 필요하다는 뜻이고, 계약에 명시돼 있다.
     */
    private fun parseMonth(raw: String?): YearMonth? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            YearMonth.parse(trimmed)
        } catch (e: DateTimeParseException) {
            throw SnackbarException("조회할 월 형식이 올바르지 않아요")
        }
    }

    /** `ActiveChallengeController` 와 같은 규칙. principal 은 userId(Long). */
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

    private fun ChallengeHistoryView.toDto(): ChallengeHistoryDto = ChallengeHistoryDto(
        challengeId = challengeId,
        // challengeDate 는 이미 KST LocalDate (ADR-0010) — 변환 없이 그대로 직렬화된다.
        challengeDate = challengeDate,
        opponentNickname = opponentNickname,
        myMission = myMission,
        opponentMission = opponentMission,
        bet = bet,
        // 뒤집기는 서비스가 이미 끝냈다 — 역할 기준 원본은 이 응답에 없다 (design §7-④).
        myResult = myResult,
    )
}
