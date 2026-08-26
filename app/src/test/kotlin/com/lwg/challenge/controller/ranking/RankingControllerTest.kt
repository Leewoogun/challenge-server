package com.lwg.challenge.controller.ranking

import com.lwg.challenge.controller.common.exception.GlobalExceptionHandler
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.ranking.LoserRankingEntry
import com.lwg.challenge.service.ranking.RankingService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

/**
 * RankingController 슬라이스 테스트 (`GET /api/v1/rankings/losers`).
 *
 * - `@WebMvcTest` 로 컨트롤러 + GlobalExceptionHandler 만 로드 (DB 불필요).
 * - `addFilters=false` 로 SecurityFilterChain 우회. SecurityContext 에 직접 principal=Long 주입.
 * - `RankingService` 는 mock — 정렬/순위 규칙은 `LoserRankingTest`, SQL 은 통합 테스트가 본다.
 *
 * ⚠️ **이 API 에는 비즈니스 예외가 없다** (계약: 7xx 0건). `SnackbarException` 변환 테스트를 넣지
 * 않은 것은 빠뜨린 게 아니라 **존재하지 않는 경로이기 때문**이다. 서비스가 읽기 한 번 + 순수 함수
 * 호출뿐이라 실패할 비즈니스 분기 자체가 없다.
 */
@WebMvcTest(controllers = [RankingController::class])
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class RankingControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var rankingService: RankingService

    private val meId = 1L

    @BeforeEach
    fun setUpAuthentication() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                meId,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    private fun entry(
        rank: Int,
        userId: Long,
        nickname: String,
        profileImageUrl: String?,
        isMe: Boolean,
        losses: Int,
        lossRate: Int,
        currentLossStreak: Int,
        totalChallenges: Int = 0,
    ) = LoserRankingEntry(
        rank = rank,
        userId = userId,
        nickname = nickname,
        profileImageUrl = profileImageUrl,
        isMe = isMe,
        losses = losses,
        lossRate = lossRate,
        currentLossStreak = currentLossStreak,
        totalChallenges = totalChallenges,
    )

    @Test
    fun `getLoserRanking - 정상 응답 3건 + 필드가 그대로 실린다`() {
        Mockito.`when`(rankingService.getLoserRanking(meId)).thenReturn(
            listOf(
                entry(1, 3L, "준혁", "https://cdn/u/3.jpg", false, 25, 78, 7, totalChallenges = 32),
                entry(2, 1L, "나", null, true, 12, 30, 0, totalChallenges = 40),
                entry(3, 7L, "민수", null, false, 0, 0, 0, totalChallenges = 0),
            ),
        )

        mockMvc.perform(get("/api/v1/rankings/losers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.rankings.length()").value(3))
            .andExpect(jsonPath("$.data.rankings[0].rank").value(1))
            .andExpect(jsonPath("$.data.rankings[0].userId").value(3))
            .andExpect(jsonPath("$.data.rankings[0].nickname").value("준혁"))
            .andExpect(jsonPath("$.data.rankings[0].profileImageUrl").value("https://cdn/u/3.jpg"))
            .andExpect(jsonPath("$.data.rankings[0].isMe").value(false))
            .andExpect(jsonPath("$.data.rankings[0].losses").value(25))
            .andExpect(jsonPath("$.data.rankings[0].lossRate").value(78))
            .andExpect(jsonPath("$.data.rankings[0].currentLossStreak").value(7))
            .andExpect(jsonPath("$.data.rankings[0].totalChallenges").value(32))
            .andExpect(jsonPath("$.data.rankings[1].rank").value(2))
            .andExpect(jsonPath("$.data.rankings[1].userId").value(1))
            .andExpect(jsonPath("$.data.rankings[1].isMe").value(true))
            .andExpect(jsonPath("$.data.rankings[2].rank").value(3))
            .andExpect(jsonPath("$.data.rankings[2].losses").value(0))
            .andExpect(jsonPath("$.data.rankings[2].lossRate").value(0))
    }

    /**
     * 🔴 **친구가 0명이어도 `rankings` 는 빈 배열이 아니라 1건(나)이다** (계약 §5 불변식).
     *
     * 다른 목록 API(`GET /friends` 등)는 0건이면 빈 배열이 정답이라 여기서도 빈 배열이 자연스러워
     * 보이는데, 이 API 만 다르다. **앱의 빈 상태 판정이 `size == 1` 이라 빈 배열로 바뀌면 앱이
     * 조용히 오작동한다** — 예외도 로그도 없이 "혼자입니다" 안내 대신 아무것도 없는 화면이 뜬다.
     */
    @Test
    fun `getLoserRanking - 친구 0명이면 빈 배열이 아니라 나 1건이다`() {
        Mockito.`when`(rankingService.getLoserRanking(meId)).thenReturn(
            listOf(entry(1, meId, "나", null, true, 0, 0, 0)),
        )

        mockMvc.perform(get("/api/v1/rankings/losers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.rankings").isArray)
            .andExpect(jsonPath("$.data.rankings.length()").value(1))
            .andExpect(jsonPath("$.data.rankings[0].isMe").value(true))
            .andExpect(jsonPath("$.data.rankings[0].userId").value(meId.toInt()))
            .andExpect(jsonPath("$.data.rankings[0].rank").value(1))
    }

    /**
     * 🔴 `profileImageUrl` 이 null 이어도 **키는 남는다** (`WireShapeContractTest` 의
     * "null 인 필드도 키는 남는다" 와 같은 취지).
     *
     * 키 부재와 값 null 은 모바일에서 서로 다른 예외라(`MissingFieldException` vs
     * `JsonConvertException`) 응답 **원문**까지 본다 — 누가 `@JsonInclude(NON_NULL)` 을 켜면
     * 프로필 없는 사용자에서 키가 통째로 사라지고 실패 모드가 조용히 바뀐다.
     */
    @Test
    fun `getLoserRanking - profileImageUrl 이 null 이어도 키가 남는다`() {
        Mockito.`when`(rankingService.getLoserRanking(meId)).thenReturn(
            listOf(entry(1, meId, "나", null, true, 3, 30, 1, totalChallenges = 10)),
        )

        val body = mockMvc.perform(get("/api/v1/rankings/losers"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .getContentAsString(Charsets.UTF_8)

        assertTrue(
            body.contains(""""profileImageUrl":null"""),
            "원문에 profileImageUrl 키가 null 값으로 남아 있어야 한다. actual=$body",
        )
    }

    /**
     * 컨트롤러가 서비스에 넘기는 userId 가 SecurityContext principal 과 같아야 한다.
     * 여기가 어긋나면 **남의 랭킹을 보게 된다** — 응답 형태는 멀쩡해서 jsonPath 로는 안 잡힌다.
     */
    @Test
    fun `getLoserRanking - service 에 SecurityContext principal 이 그대로 전달된다`() {
        Mockito.`when`(rankingService.getLoserRanking(meId)).thenReturn(
            listOf(entry(1, meId, "나", null, true, 0, 0, 0)),
        )

        mockMvc.perform(get("/api/v1/rankings/losers")).andExpect(status().isOk)

        Mockito.verify(rankingService).getLoserRanking(meId)
    }
}
