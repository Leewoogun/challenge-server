package com.lwg.challenge.controller.challenge

import com.lwg.challenge.controller.common.exception.GlobalExceptionHandler
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.OneButtonDialogException
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.service.challenge.ChallengeCommandService
import com.lwg.challenge.service.challenge.ChallengeDetailService
import com.lwg.challenge.service.challenge.ChallengeDetailView
import com.lwg.challenge.service.challenge.ContractView
import com.lwg.challenge.service.challenge.PartyView
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import java.time.LocalDateTime

/**
 * `GET /api/v1/challenges/{id}` 슬라이스 테스트 (soul-oath #24).
 *
 * ## 왜 뒤늦게 생겼나 — 이 엔드포인트는 테스트가 **0건**이었다
 *
 * `ChallengeDetailService` 는 `ChallengeCommandControllerTest` 에 `@MockitoBean` 으로 **주입만**
 * 돼 있었다(슬라이스 컨텍스트 로드용). 즉 **서버는 자기가 `contract: null` 을 내보낸다는 걸
 * 한 번도 확인한 적이 없다.** 실사용에서 모바일 상세 화면이 깨지고 나서야 드러났다.
 *
 * ## 🔴 이 테스트의 핵심은 "키가 없는 것"과 "키가 null 인 것"의 구분이다
 *
 * 모바일 kotlinx.serialization 기준으로 둘은 **다른 실패**를 낸다:
 * ```
 * 키 자체가 없음        → MissingFieldException  (기본값이 없으면)
 * 키가 있고 값이 null   → JsonConvertException   (필드가 non-nullable 이면)
 * ```
 * 그래서 `jsonPath(...).doesNotExist()` / `.value(null)` 로만 보면 **둘을 구별하지 못한다.**
 * 아래 null 케이스들은 **원문 문자열에 `"contract":null` 이 실제로 들어 있는지**까지 확인한다.
 *
 * 이게 회귀 방지로 중요한 이유: 누군가 `@JsonInclude(NON_NULL)` 을 켜면 **응답에서 키가 사라지고**
 * 모바일 실패 모드가 조용히 바뀐다. 서버 테스트는 전부 통과한 채로.
 */
@WebMvcTest(controllers = [ChallengeCommandController::class])
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class ChallengeDetailControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var challengeDetailService: ChallengeDetailService

    /** 같은 컨트롤러가 주입받으므로 슬라이스 컨텍스트 로드에 필요. */
    @MockitoBean
    lateinit var challengeCommandService: ChallengeCommandService

    private val meId = 42L

    @BeforeEach
    fun setUpAuthentication() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                meId,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    // ─── 1. 정상 (모든 필드 채워짐) ───────────────────────────────

    @Test
    fun `양측 서명된 계약서를 역할 그대로 반환한다`() {
        Mockito.`when`(challengeDetailService.getDetail(anyLong(), anyLong())).thenReturn(fullView())

        mockMvc.perform(get("/api/v1/challenges/7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.challengeId").value(7))
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
            // 역할 그대로 — "나/상대" 로 뒤집지 않는다 (계약 §3)
            .andExpect(jsonPath("$.data.challenger.userId").value(1))
            .andExpect(jsonPath("$.data.opponent.userId").value(14))
            // ADR-0010 wire 포맷
            .andExpect(jsonPath("$.data.deadline").value("2026-08-04 00:00:00"))
            .andExpect(jsonPath("$.data.challengeDate").value("2026-08-03"))
            .andExpect(jsonPath("$.data.contract.challengerSignedAt").value("2026-08-03 11:20:00"))
    }

    @Test
    fun `서명은 문자열로 나간다 - 객체가 아니다`() {
        Mockito.`when`(challengeDetailService.getDetail(anyLong(), anyLong())).thenReturn(fullView())

        val body = mockMvc.perform(get("/api/v1/challenges/7"))
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        // 🔴 @JsonRawValue 가 다시 붙으면 **객체**로 나가고, 모바일이 JsonElement 재인코딩을 타
        //    §4.2 무손실이 깨진다. 이스케이프된 문자열인지 원문으로 확인한다.
        //    문자열이면 `"challengerSignature":"{\"v\":1...`  /  객체면 `"challengerSignature":{"v":1...`
        assertTrue(
            body.contains("\"challengerSignature\":\"{\\\"v\\\":1"),
            "서명이 이스케이프된 JSON 문자열이어야 한다(객체 아님). actual=$body",
        )
        assertTrue(
            !body.contains("\"challengerSignature\":{"),
            "서명이 객체로 나가고 있다 — @JsonRawValue 가 붙었는지 확인해라. actual=$body",
        )
    }

    // ─── 2. 🔴 contract: null (레거시 — #24) ─────────────────────

    @Test
    fun `계약서가 없으면 contract 는 키가 존재하고 값이 null 이다`() {
        // soul-oath 배포 이전에 만들어진 챌린지(실 DB id=11). 배포 이후로는 도달 불가하지만,
        // 백필로 없앨 수 없다 — 서명을 만들면 없던 맹세를 날조하는 것이고,
        // 미서명으로 채우면 "is_finalized=true 인 시점에만 IN_PROGRESS" 불변식을 깬다.
        Mockito.`when`(challengeDetailService.getDetail(anyLong(), anyLong()))
            .thenReturn(fullView().copy(contract = null))

        val body = mockMvc.perform(get("/api/v1/challenges/11"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            // 나머지 필드는 정상이어야 한다 — 화면 전체가 깨지면 안 된다.
            .andExpect(jsonPath("$.data.challengeId").value(7))
            .andExpect(jsonPath("$.data.challenger.nickname").value("이우건"))
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(
            body.contains(""""contract":null"""),
            "키가 빠지면 모바일 실패 모드가 MissingFieldException 으로 바뀐다. actual=$body",
        )
    }

    @Test
    fun `수락 전이면 opponent mission 이 키 존재 + null 이다`() {
        Mockito.`when`(challengeDetailService.getDetail(anyLong(), anyLong()))
            .thenReturn(fullView().copy(opponent = fullView().opponent.copy(mission = null)))

        val body = mockMvc.perform(get("/api/v1/challenges/7"))
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(body.contains(""""mission":null"""), "actual=$body")
    }

    @Test
    fun `미서명 필드는 키 존재 + null 이다`() {
        Mockito.`when`(challengeDetailService.getDetail(anyLong(), anyLong())).thenReturn(
            fullView().copy(
                contract = fullView().contract!!.copy(
                    isFinalized = false,
                    opponentSignature = null,
                    opponentSignedAt = null,
                ),
            ),
        )

        val body = mockMvc.perform(get("/api/v1/challenges/7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.contract.isFinalized").value(false))
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(body.contains(""""opponentSignature":null"""), "actual=$body")
        assertTrue(body.contains(""""opponentSignedAt":null"""), "actual=$body")
    }

    // ─── 3. 🔴 null 최대 — 모바일 픽스처 원본 ─────────────────────

    @Test
    fun `모든 nullable 이 동시에 null 인 응답도 정상 직렬화된다`() {
        // **실서버로는 만들 수 없는 조합**이다(레거시 챌린지가 미수락 상태일 수 없다).
        // 그래서 라이브 응답 샘플링으로는 절대 안 나오고, 계약이 허용하는 최대 null 조합을
        // 여기서 합성해 mobile-dev 픽스처의 원본으로 넘긴다 (#24 Q3).
        Mockito.`when`(challengeDetailService.getDetail(anyLong(), anyLong())).thenReturn(
            fullView().copy(
                challenger = fullView().challenger.copy(profileImageUrl = null, mission = null),
                opponent = fullView().opponent.copy(profileImageUrl = null, mission = null),
                contract = null,
            ),
        )

        val body = mockMvc.perform(get("/api/v1/challenges/11"))
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        for (key in listOf("profileImageUrl", "mission", "contract")) {
            assertTrue(body.contains(""""$key":null"""), "$key 가 null 로 나가야 한다. actual=$body")
        }
        // non-nullable 은 여전히 값이 있어야 한다 — null 조합이라고 전부 비면 안 된다.
        assertTrue(body.contains(""""betContent":"커피 사기""""), "actual=$body")
    }

    // ─── 4. 에러 ─────────────────────────────────────────────────

    @Test
    fun `당사자가 아니면 code 700 + 확정 문구`() {
        Mockito.`when`(challengeDetailService.getDetail(anyLong(), anyLong()))
            .thenThrow(SnackbarException(ChallengeDetailService.MSG_NOT_MINE))

        mockMvc.perform(get("/api/v1/challenges/7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("내 챌린지가 아니에요"))
    }

    @Test
    fun `없는 id 는 code 705 + 확정 문구`() {
        Mockito.`when`(challengeDetailService.getDetail(anyLong(), anyLong()))
            .thenThrow(OneButtonDialogException(ChallengeDetailService.MSG_NOT_FOUND))

        mockMvc.perform(get("/api/v1/challenges/9999"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.ONE_BUTTON_DIALOG_ERROR))
            .andExpect(jsonPath("$.message").value("챌린지를 찾을 수 없어요"))
    }

    // ─── fixtures ────────────────────────────────────────────────

    private fun fullView() = ChallengeDetailView(
        challengeId = 7L,
        status = "IN_PROGRESS",
        challengeDate = "2026-08-03",
        deadline = LocalDateTime.of(2026, 8, 4, 0, 0, 0),
        betContent = "커피 사기",
        challenger = PartyView(
            userId = 1L,
            nickname = "이우건",
            profileImageUrl = "https://example.com/a.png",
            mission = "운동 1시간",
        ),
        opponent = PartyView(
            userId = 14L,
            nickname = "테스터1",
            profileImageUrl = "https://example.com/b.png",
            mission = "책 30페이지",
        ),
        contract = ContractView(
            content = "영혼의 맹세\n\n...",
            isFinalized = true,
            challengerSignature = """{"v":1,"g":1000,"s":[[123,456,130,460]]}""",
            challengerSignedAt = LocalDateTime.of(2026, 8, 3, 11, 20, 0),
            opponentSignature = """{"v":1,"g":1000,"s":[[700,200,705,210]]}""",
            opponentSignedAt = LocalDateTime.of(2026, 8, 3, 11, 25, 0),
        ),
    )
}
