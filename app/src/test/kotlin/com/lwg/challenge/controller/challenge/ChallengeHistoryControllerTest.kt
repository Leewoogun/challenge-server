package com.lwg.challenge.controller.challenge

import com.lwg.challenge.controller.common.exception.GlobalExceptionHandler
import com.lwg.challenge.domain.challenge.Outcome
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.service.challenge.ChallengeCommandService
import com.lwg.challenge.service.challenge.ChallengeDetailService
import com.lwg.challenge.service.challenge.ChallengeForfeitService
import com.lwg.challenge.service.challenge.ChallengeHistoryPage
import com.lwg.challenge.service.challenge.ChallengeHistoryService
import com.lwg.challenge.service.challenge.ChallengeHistoryView
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.YearMonth

/**
 * [ChallengeHistoryController] 슬라이스 테스트 — `GET /api/v1/challenges/history`
 * (mypage T-B1, 2026-08-28 월 단위 개정).
 *
 * - `@WebMvcTest` 로 컨트롤러 + GlobalExceptionHandler 만 로드 (DB 불필요).
 * - `addFilters=false` 로 SecurityFilterChain 우회. SecurityContext 에 직접 principal=Long 주입.
 * - 정렬·뒤집기·경계 계산 규칙은 `ChallengeHistoryServiceTest`, SQL 은 실구동이 본다. 여기서
 *   보는 것은 **wire 로 나가는 모양**, **`?month=` 가 서비스까지 옳게 도달하는가**, 그리고
 *   **경로가 어느 핸들러에 붙는가**다.
 *
 * ## 🔴 [ChallengeCommandController] 를 함께 로드한 이유는 하나뿐이다
 *
 * 그쪽에 `@GetMapping("/challenges/{id}")` 가 있어 `/challenges/history` 가 **변수 경로로 샐
 * 수 있는가**를 봐야 한다. 컨트롤러 하나만 로드하면 경쟁자가 없어 그 질문에 답할 수 없고,
 * 테스트는 통과하는데 운영에서 `id=history` 파싱 에러가 난다.
 *
 * ## ⚠️ 개정으로 이 컨트롤러에 **자기 예외가 생겼다**
 *
 * 1차에는 *"서비스가 읽기 두 번뿐이라 실패할 분기가 없다"* 였다. 이제 `?month=` 파싱이
 * 컨트롤러 안에 있고 형식 오류를 `SnackbarException`(700)으로 던진다 — 그 축을 아래에서 본다.
 */
@WebMvcTest(controllers = [ChallengeHistoryController::class, ChallengeCommandController::class])
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class ChallengeHistoryControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var challengeHistoryService: ChallengeHistoryService

    // 아래 둘은 ChallengeCommandController 를 띄우기 위한 것이다 — 경로 우선순위 테스트에서만 쓴다.
    @MockitoBean
    lateinit var challengeCommandService: ChallengeCommandService

    @MockitoBean
    lateinit var challengeDetailService: ChallengeDetailService

    @MockitoBean
    lateinit var challengeForfeitService: ChallengeForfeitService

    private val meId = 1L

    @BeforeEach
    fun setUpAuthentication() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(meId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
    }

    private fun view(
        challengeId: Long,
        challengeDate: LocalDate,
        opponentNickname: String = "민수",
        myResult: Outcome = Outcome.WIN,
    ) = ChallengeHistoryView(
        challengeId = challengeId,
        challengeDate = challengeDate,
        opponentNickname = opponentNickname,
        myMission = "오늘 운동 1시간 하기",
        opponentMission = "책 30페이지 읽기",
        bet = "커피 사기",
        myResult = myResult,
    )

    private fun page(
        month: YearMonth = YearMonth.of(2026, 8),
        firstArchivedMonth: YearMonth? = YearMonth.of(2026, 3),
        histories: List<ChallengeHistoryView> = emptyList(),
    ) = ChallengeHistoryPage(
        month = month,
        firstArchivedMonth = firstArchivedMonth,
        histories = histories,
    )

    /** `?month=` 없이 부르면 서비스에 `null` 이 간다 — 그 스텁을 세운다. */
    private fun stubDefaultMonth(page: ChallengeHistoryPage) {
        Mockito.`when`(challengeHistoryService.getHistories(meId, null)).thenReturn(page)
    }

    // ─── 200 shape ───────────────────────────────────────────────

    @Test
    fun `getHistories - 정상 응답 2건 + 필드가 그대로 실린다`() {
        stubDefaultMonth(
            page(
                histories = listOf(
                    view(1001L, LocalDate.of(2026, 8, 24)),
                    view(1000L, LocalDate.of(2026, 8, 3), opponentNickname = "탈퇴한 사용자", myResult = Outcome.BOTH_LOSE),
                ),
            ),
        )

        mockMvc.perform(get("/api/v1/challenges/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.histories.length()").value(2))
            .andExpect(jsonPath("$.data.histories[0].challengeId").value(1001))
            // 🔴 ADR-0010 평문 ISO date — `[2026,8,24]` 배열이 나가면 모바일이 즉시 깨진다.
            .andExpect(jsonPath("$.data.histories[0].challengeDate").value("2026-08-24"))
            .andExpect(jsonPath("$.data.histories[0].opponentNickname").value("민수"))
            .andExpect(jsonPath("$.data.histories[0].myMission").value("오늘 운동 1시간 하기"))
            .andExpect(jsonPath("$.data.histories[0].opponentMission").value("책 30페이지 읽기"))
            .andExpect(jsonPath("$.data.histories[0].bet").value("커피 사기"))
            .andExpect(jsonPath("$.data.histories[0].myResult").value("WIN"))
            // 탈퇴자도 닉네임이 non-null 이다 — 익명화된 값이 그대로 나간다 (mypage T-B2).
            .andExpect(jsonPath("$.data.histories[1].opponentNickname").value("탈퇴한 사용자"))
            // 🔴 BOTH_LOSE 는 LOSE 로 접히지 않는다 (`Outcome` KDoc).
            .andExpect(jsonPath("$.data.histories[1].myResult").value("BOTH_LOSE"))
    }

    /**
     * 🔴 개정으로 생긴 2필드. 둘 다 **`"yyyy-MM"` 문자열**이어야 한다 —
     * `YearMonth` 를 `@JsonFormat` 없이 두면 `[2026,8]` 배열로 샌다.
     * `challengeDate` 가 `[2026,8,24]` 로 새던 회귀가 이 프로젝트에 실제로 있었다.
     */
    @Test
    fun `getHistories - month 와 firstArchivedMonth 가 문자열로 나간다`() {
        stubDefaultMonth(page(month = YearMonth.of(2026, 8), firstArchivedMonth = YearMonth.of(2026, 3)))

        val body = mockMvc.perform(get("/api/v1/challenges/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.month").value("2026-08"))
            .andExpect(jsonPath("$.data.firstArchivedMonth").value("2026-03"))
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(body.contains(""""month":"2026-08""""), "month 가 배열로 새면 안 된다: $body")
        assertTrue(
            body.contains(""""firstArchivedMonth":"2026-03""""),
            "firstArchivedMonth 가 배열로 새면 안 된다: $body",
        )
    }

    /**
     * 🔴 **2차 개정 회귀 방지 — 응답에 하한 필드가 없다.**
     *
     * 과거 끝이 사라졌으므로 `joinedMonth` 류의 경계 필드가 wire 에 들어오면 안 된다.
     * 들어오는 순간 앱이 다시 `<` 를 막을 근거가 생긴다 — 지금 폐기한 그 규칙이다.
     */
    @Test
    fun `getHistories - 응답에 하한 경계 필드가 없다`() {
        stubDefaultMonth(page())

        val body = mockMvc.perform(get("/api/v1/challenges/history"))
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(!body.contains("joinedMonth"), "하한 필드가 되살아났다: $body")
        assertTrue(!body.contains("hasPrevious"), body)
    }

    /**
     * 🔴 **기록 0건이면 `firstArchivedMonth` 가 `null` 이고, 키는 그대로 실린다.**
     *
     * 이 프로젝트 Jackson 에 `@JsonInclude(NON_NULL)` 이 없다는 사실에 앱이 기대고 있다 —
     * 모바일이 키 존재를 전제해도 되는 근거를 여기서 원문으로 고정한다.
     */
    @Test
    fun `getHistories - 기록 0건이면 firstArchivedMonth 가 null 이고 키는 남는다`() {
        stubDefaultMonth(page(firstArchivedMonth = null))

        val body = mockMvc.perform(get("/api/v1/challenges/history"))
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(
            body.contains(""""firstArchivedMonth":null"""),
            "null 이어도 키가 사라지면 안 된다 (앱이 키 존재를 전제한다): $body",
        )
    }

    /** 🔴 `?month=` 가 `YearMonth` 로 파싱돼 서비스까지 도달한다. */
    @Test
    fun `getHistories - month 파라미터가 서비스에 그대로 전달된다`() {
        Mockito.`when`(challengeHistoryService.getHistories(meId, YearMonth.of(2026, 3)))
            .thenReturn(page(month = YearMonth.of(2026, 3)))

        mockMvc.perform(get("/api/v1/challenges/history").param("month", "2026-03"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.month").value("2026-03"))
            .andExpect(jsonPath("$.data.firstArchivedMonth").value("2026-03"))

        Mockito.verify(challengeHistoryService).getHistories(meId, YearMonth.of(2026, 3))
    }

    /**
     * 🔴 파라미터가 없으면 **null 이 간다** — 이번 달을 컨트롤러가 계산하지 않는다.
     *
     * 기본값을 서비스(= `Clock` 을 가진 쪽)가 채우는 것이 결정이다. 컨트롤러가 채우면
     * 상한과 하한이 서로 다른 시계에서 나올 수 있다.
     */
    @Test
    fun `getHistories - month 가 없으면 서비스에 null 이 전달된다`() {
        stubDefaultMonth(page())

        mockMvc.perform(get("/api/v1/challenges/history")).andExpect(status().isOk)

        Mockito.verify(challengeHistoryService).getHistories(meId, null)
    }

    /** 빈 문자열(`?month=`)도 미지정과 같이 다룬다 — 앱이 빈 값을 붙여 보내도 첫 진입이 깨지지 않는다. */
    @Test
    fun `getHistories - month 가 빈 문자열이면 미지정과 같다`() {
        stubDefaultMonth(page())

        mockMvc.perform(get("/api/v1/challenges/history").param("month", "")).andExpect(status().isOk)

        Mockito.verify(challengeHistoryService).getHistories(meId, null)
    }

    /**
     * 🔴 카드에 **역할 기준 `result` 와 인증 상태 2필드가 없다** (design §7-④ 확정).
     *
     * 나중에 누가 *"홈 카드와 모양을 맞추자"* 며 되살리기 쉬운 필드들이라 원문으로 못박는다.
     * `result` 가 함께 내려가면 앱이 어느 축을 믿을지 고르게 되고, 그 선택이 화면마다 갈린다.
     */
    @Test
    fun `getHistories - result 와 인증 상태 필드는 응답에 없다`() {
        stubDefaultMonth(page(histories = listOf(view(1001L, LocalDate.of(2026, 8, 24)))))

        val body = mockMvc.perform(get("/api/v1/challenges/history"))
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(!body.contains(""""result":"""), "역할 기준 result 가 새면 안 된다: $body")
        assertTrue(!body.contains("VerificationStatus"), body)
        assertTrue(!body.contains("myVerificationStatus"), body)
        assertTrue(!body.contains("opponentVerificationStatus"), body)
    }

    /**
     * 🔴 빈 달은 **정상 응답**이다 — 빈 달을 건너뛰지 않는 것이 확정된 동작이라 `[]` 가 자주 온다.
     * 그리고 이때도 `firstArchivedMonth` 는 실려 나가야 한다. 안 그러면 앱이 빈 달에서
     * **화살표를 그릴 근거를 잃고**, 이 달만 빈 것인지 보관함이 통째로 빈 것인지도 구분 못 한다.
     */
    @Test
    fun `getHistories - 빈 달도 경계 신호와 함께 정상 응답한다`() {
        stubDefaultMonth(page(firstArchivedMonth = YearMonth.of(2026, 3), histories = emptyList()))

        mockMvc.perform(get("/api/v1/challenges/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.histories").isArray)
            .andExpect(jsonPath("$.data.histories.length()").value(0))
            .andExpect(jsonPath("$.data.firstArchivedMonth").value("2026-03"))
    }

    // ─── 🔴 month 형식 오류 = 700 (컨트롤러 자신의 예외) ──────────

    /**
     * 🔴 **HTTP 500 이 아니라 200 + code=700 이다.**
     *
     * `@RequestParam month: YearMonth?` 로 선언했다면 Spring 이
     * `MethodArgumentTypeMismatchException` 을 던지고, `GlobalExceptionHandler` 에 그 핸들러가
     * 없어 `handleUncaught` → **HTTP 500** 이 나갔을 자리다. 클라이언트가 잘못 보낸 쿼리
     * 파라미터 하나가 서버 장애로 보고되는 것을 막는 것이 문자열 파싱의 이유다
     * (ADR-0002: 5xx 는 인프라 장애 전용).
     */
    @Test
    fun `getHistories - month 형식이 틀리면 HTTP 200 + code 700`() {
        mockMvc.perform(get("/api/v1/challenges/history").param("month", "2026-8"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))

        Mockito.verify(challengeHistoryService, Mockito.never()).getHistories(anyLong(), any())
    }

    @Test
    fun `getHistories - month 가 아예 날짜가 아니어도 700`() {
        mockMvc.perform(get("/api/v1/challenges/history").param("month", "지난달"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))

        mockMvc.perform(get("/api/v1/challenges/history").param("month", "2026-08-24"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))

        Mockito.verify(challengeHistoryService, Mockito.never()).getHistories(anyLong(), any())
    }

    // ─── 인증 ────────────────────────────────────────────────────

    /** SecurityContext 가 비면 컨트롤러의 `currentUserId()` 가 401 로 끊는다. */
    @Test
    fun `getHistories - 인증 정보가 없으면 HTTP 401 + code 401`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/challenges/history"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))

        Mockito.verify(challengeHistoryService, Mockito.never()).getHistories(anyLong(), any())
    }

    /**
     * 컨트롤러가 서비스에 넘기는 userId 가 SecurityContext principal 과 같아야 한다.
     * 여기가 어긋나면 **남의 계약서를 보게 된다** — 응답 형태는 멀쩡해서 jsonPath 로는 안 잡힌다.
     */
    @Test
    fun `getHistories - service 에 SecurityContext principal 이 그대로 전달된다`() {
        stubDefaultMonth(page())

        mockMvc.perform(get("/api/v1/challenges/history")).andExpect(status().isOk)

        Mockito.verify(challengeHistoryService).getHistories(meId, null)
    }

    // ─── 🔴 경로 우선순위 ────────────────────────────────────────

    /**
     * 🔴 `/challenges/history` 가 `/challenges/{id}` 로 **새지 않는다.**
     *
     * Spring 은 리터럴 경로를 변수 경로보다 우선하므로 이건 원래 안전하다 — 이 레포에도 선례가
     * 둘 있다(`/challenges/active`, `/challenges/received` 가 같은 `{id}` 와 공존 중). 즉 새로운
     * 위험이 아니다. 그래도 고정하는 이유는 **새면 조용히 실패하지 않기 때문**이다:
     * `id=history` 를 Long 으로 파싱하려다 500 이 난다. 한 줄로 막을 수 있는 회귀다.
     */
    @Test
    fun `history 는 상세 핸들러로 새지 않는다`() {
        stubDefaultMonth(page())

        mockMvc.perform(get("/api/v1/challenges/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.histories").isArray)

        Mockito.verify(challengeHistoryService).getHistories(meId, null)
        Mockito.verify(challengeDetailService, Mockito.never()).getDetail(anyLong(), anyLong())
    }
}
