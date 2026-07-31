package com.lwg.challenge.controller.challenge

import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.controller.common.exception.GlobalExceptionHandler
import com.lwg.challenge.core.challenge.DeadlineType
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.ReceivedChallenge
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.OneButtonDialogException
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.service.challenge.ChallengeCommandService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * ChallengeCommandController 슬라이스 테스트 (challenge-create T-B4).
 *
 * - `@WebMvcTest` 로 컨트롤러 + GlobalExceptionHandler 만 로드 (DB 불필요).
 * - `addFilters=false` 로 SecurityFilterChain 우회. SecurityContext 에 직접 principal=Long 주입.
 * - `ChallengeCommandService` 는 mock — 비즈니스 분기는 `ChallengeCommandServiceTest` 가 커버한다.
 *
 * **이 테스트의 존재 이유는 대부분 "직렬화 고정"이다.** mobile-dev 가 지적한 두 가지가 여기서 막힌다:
 *  1. `challengeDate` 가 `[2026,7,28]` 배열로 나가면 모바일 `String` 파싱이 깨진다.
 *  2. `deadline` / `createdAt` 이 `"yyyy-MM-dd HH:mm:ss"` 를 벗어나면(=`T` 구분자나 나노초가 섞이면)
 *     모바일 커스텀 파서가 실패한다 — ADR-0010 이후 관대하게 받아주지 않는다.
 * 그래서 jsonPath 로 **문자열 그대로** assert 한다.
 */
@WebMvcTest(controllers = [ChallengeCommandController::class])
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class ChallengeCommandControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

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

    // ─── 1. POST /challenges ─────────────────────────────────────

    @Test
    fun `create - 정상 응답 + challengeDate 는 평문 ISO date 문자열이다`() {
        Mockito.`when`(
            challengeCommandService.create(meId, 7L, "운동 1시간", "커피 사기", DeadlineType.TODAY),
        ).thenReturn(challenge(id = 100L, status = ChallengeStatus.PENDING))

        mockMvc.perform(
            post("/api/v1/challenges")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"opponentId":7,"myMission":"운동 1시간","betContent":"커피 사기","deadlineType":"TODAY"}
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.challengeId").value(100))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            // 🔴 배열 `[2026,7,28]` 회귀 방지 — 모바일은 String 으로 받는다.
            .andExpect(jsonPath("$.data.challengeDate").value("2026-07-28"))
            // 🔴 Z suffix 고정 — `+09:00` 이나 나노초가 섞이면 안 된다.
            .andExpect(jsonPath("$.data.deadline").value("2026-07-29 00:00:00"))
    }

    @Test
    fun `create - 친구가 아니면 code 700 + 확정 문구`() {
        Mockito.`when`(
            challengeCommandService.create(meId, 7L, "운동", "커피", DeadlineType.TODAY),
        ).thenThrow(SnackbarException(ChallengeCommandService.MSG_NOT_FRIEND))

        mockMvc.perform(
            post("/api/v1/challenges")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"opponentId":7,"myMission":"운동","betContent":"커피","deadlineType":"TODAY"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("친구에게만 챌린지를 걸 수 있어요"))
    }

    @Test
    fun `create - 역방향 중복은 받은 도전장으로 안내하는 문구다`() {
        Mockito.`when`(
            challengeCommandService.create(meId, 7L, "운동", "커피", DeadlineType.TODAY),
        ).thenThrow(SnackbarException(ChallengeCommandService.MSG_DUPLICATE_REVERSE))

        mockMvc.perform(
            post("/api/v1/challenges")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"opponentId":7,"myMission":"운동","betContent":"커피","deadlineType":"TODAY"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("이 친구가 이미 도전장을 보냈어요. 받은 도전장을 확인해보세요"))
    }

    @Test
    fun `create - 미션 101자는 bean validation 이 code 700 으로 막는다`() {
        val tooLong = "가".repeat(101)

        mockMvc.perform(
            post("/api/v1/challenges")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "opponentId" to 7,
                            "myMission" to tooLong,
                            "betContent" to "커피",
                            "deadlineType" to "TODAY",
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("미션은 1자 이상 100자 이하로 입력해주세요"))
    }

    @Test
    fun `create - opponentId 가 0 이하면 code 700`() {
        mockMvc.perform(
            post("/api/v1/challenges")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"opponentId":0,"myMission":"운동","betContent":"커피","deadlineType":"TODAY"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
    }

    @Test
    fun `create - deadlineType TOMORROW 도 바인딩된다`() {
        Mockito.`when`(
            challengeCommandService.create(meId, 7L, "운동", "커피", DeadlineType.TOMORROW),
        ).thenReturn(
            challenge(
                id = 101L,
                status = ChallengeStatus.PENDING,
                challengeDate = LocalDate.of(2026, 7, 29),
                deadline = LocalDateTime.of(2026, 7, 30, 0, 0),
            ),
        )

        mockMvc.perform(
            post("/api/v1/challenges")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"opponentId":7,"myMission":"운동","betContent":"커피","deadlineType":"TOMORROW"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.challengeDate").value("2026-07-29"))
            .andExpect(jsonPath("$.data.deadline").value("2026-07-30 00:00:00"))
    }

    // ─── 2. GET /challenges/received ─────────────────────────────

    @Test
    fun `listReceived - 전 필드 직렬화 + createdAt 초 단위 절삭`() {
        Mockito.`when`(challengeCommandService.listReceived(meId)).thenReturn(
            listOf(
                ReceivedChallenge(
                    challengeId = 7L,
                    challengerId = 42L,
                    challengerNickname = "민수",
                    challengerProfileImageUrl = "https://cdn/u/42.jpg",
                    challengerMission = "오늘 운동 1시간 하기",
                    betContent = "커피 사기",
                    challengeDate = LocalDate.of(2026, 7, 28),
                    deadline = LocalDateTime.of(2026, 7, 29, 0, 0),
                    // 나노초가 붙은 값 — 응답에서는 잘려야 한다.
                    createdAt = LocalDateTime.of(2026, 7, 28, 2, 11, 0, 123_456_000),
                ),
            ),
        )

        mockMvc.perform(get("/api/v1/challenges/received"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.challenges.length()").value(1))
            .andExpect(jsonPath("$.data.challenges[0].challengeId").value(7))
            .andExpect(jsonPath("$.data.challenges[0].challengerId").value(42))
            .andExpect(jsonPath("$.data.challenges[0].challengerNickname").value("민수"))
            .andExpect(jsonPath("$.data.challenges[0].challengerProfileImageUrl").value("https://cdn/u/42.jpg"))
            .andExpect(jsonPath("$.data.challenges[0].challengerMission").value("오늘 운동 1시간 하기"))
            .andExpect(jsonPath("$.data.challenges[0].betContent").value("커피 사기"))
            .andExpect(jsonPath("$.data.challenges[0].challengeDate").value("2026-07-28"))
            .andExpect(jsonPath("$.data.challenges[0].deadline").value("2026-07-29 00:00:00"))
            // 🔴 `.123456` 이 남으면 안 된다 (api-contract 확정: 초 단위 절삭).
            .andExpect(jsonPath("$.data.challenges[0].createdAt").value("2026-07-28 02:11:00"))
    }

    @Test
    fun `listReceived - 프로필 이미지가 없으면 null 로 나간다`() {
        Mockito.`when`(challengeCommandService.listReceived(meId)).thenReturn(
            listOf(
                ReceivedChallenge(
                    challengeId = 7L,
                    challengerId = 42L,
                    challengerNickname = "민수",
                    challengerProfileImageUrl = null,
                    challengerMission = "운동",
                    betContent = "커피",
                    challengeDate = LocalDate.of(2026, 7, 28),
                    deadline = LocalDateTime.of(2026, 7, 29, 0, 0),
                    createdAt = LocalDateTime.of(2026, 7, 28, 2, 11, 0),
                ),
            ),
        )

        mockMvc.perform(get("/api/v1/challenges/received"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.challenges[0].challengerProfileImageUrl").doesNotExist())
            // nullable 이 아닌 필드는 계속 존재해야 한다.
            .andExpect(jsonPath("$.data.challenges[0].challengerNickname").value("민수"))
    }

    @Test
    fun `listReceived - 0건이면 null 이 아니라 빈 배열`() {
        Mockito.`when`(challengeCommandService.listReceived(meId)).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/challenges/received"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.challenges").isArray)
            .andExpect(jsonPath("$.data.challenges.length()").value(0))
    }

    // ─── 3. POST /challenges/{id}/accept ─────────────────────────

    @Test
    fun `accept - IN_PROGRESS 응답`() {
        Mockito.`when`(challengeCommandService.accept(meId, 7L, "책 30페이지 읽기"))
            .thenReturn(challenge(id = 7L, status = ChallengeStatus.IN_PROGRESS))

        mockMvc.perform(
            post("/api/v1/challenges/7/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"myMission":"책 30페이지 읽기"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.challengeId").value(7))
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
    }

    @Test
    fun `accept - 이미 처리된 챌린지는 code 705`() {
        Mockito.`when`(challengeCommandService.accept(anyLong(), anyLong(), anyString()))
            .thenThrow(OneButtonDialogException(ChallengeCommandService.MSG_ALREADY_HANDLED))

        mockMvc.perform(
            post("/api/v1/challenges/7/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"myMission":"책"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.ONE_BUTTON_DIALOG_ERROR))
            .andExpect(jsonPath("$.message").value("이미 처리된 챌린지예요"))
    }

    @Test
    fun `accept - 마감 경과는 code 705 + 확정 문구`() {
        Mockito.`when`(challengeCommandService.accept(anyLong(), anyLong(), anyString()))
            .thenThrow(OneButtonDialogException(ChallengeCommandService.MSG_EXPIRED))

        mockMvc.perform(
            post("/api/v1/challenges/7/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"myMission":"책"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.ONE_BUTTON_DIALOG_ERROR))
            .andExpect(jsonPath("$.message").value("마감이 지난 챌린지예요"))
    }

    @Test
    fun `accept - 당사자가 아니면 code 700 + 사용자가 이해할 수 있는 문구`() {
        Mockito.`when`(challengeCommandService.accept(anyLong(), anyLong(), anyString()))
            .thenThrow(SnackbarException(ChallengeCommandService.MSG_NOT_MY_RECEIVED))

        mockMvc.perform(
            post("/api/v1/challenges/7/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"myMission":"책"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            // "권한이 없어요" 는 사용자에게 맥락이 없어 교체된 문구다.
            .andExpect(jsonPath("$.message").value("내가 받은 도전장이 아니에요"))
    }

    // ─── 4. POST /challenges/{id}/reject ─────────────────────────

    @Test
    fun `reject - REJECTED 응답`() {
        Mockito.`when`(challengeCommandService.reject(meId, 7L))
            .thenReturn(challenge(id = 7L, status = ChallengeStatus.REJECTED))

        mockMvc.perform(post("/api/v1/challenges/7/reject"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.challengeId").value(7))
            .andExpect(jsonPath("$.data.status").value("REJECTED"))
    }

    @Test
    fun `reject - 존재하지 않는 id 는 code 705`() {
        Mockito.`when`(challengeCommandService.reject(anyLong(), anyLong()))
            .thenThrow(OneButtonDialogException(ChallengeCommandService.MSG_NOT_FOUND))

        mockMvc.perform(post("/api/v1/challenges/999/reject"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.ONE_BUTTON_DIALOG_ERROR))
            .andExpect(jsonPath("$.message").value("챌린지를 찾을 수 없어요"))
    }

    // ─── 5. DELETE /challenges/{id} ──────────────────────────────

    @Test
    fun `cancel - data challengeId 를 돌려준다`() {
        mockMvc.perform(delete("/api/v1/challenges/7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            // friends 의 CancelFriendRequestResponse 와 같은 모양 (프로젝트 일관성).
            .andExpect(jsonPath("$.data.challengeId").value(7))
    }

    @Test
    fun `cancel - 챌린저가 아니면 code 700 + 보낸 도전장 문구`() {
        Mockito.doThrow(SnackbarException(ChallengeCommandService.MSG_NOT_MY_SENT))
            .`when`(challengeCommandService).cancel(anyLong(), anyLong())

        mockMvc.perform(delete("/api/v1/challenges/7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("내가 보낸 도전장이 아니에요"))
    }

    @Test
    fun `cancel - 이미 수락된 챌린지는 code 705`() {
        Mockito.doThrow(OneButtonDialogException(ChallengeCommandService.MSG_ALREADY_HANDLED))
            .`when`(challengeCommandService).cancel(anyLong(), anyLong())

        mockMvc.perform(delete("/api/v1/challenges/7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.ONE_BUTTON_DIALOG_ERROR))
            .andExpect(jsonPath("$.message").value("이미 처리된 챌린지예요"))
    }

    // ─── fixtures ────────────────────────────────────────────────

    private fun challenge(
        id: Long,
        status: ChallengeStatus,
        challengeDate: LocalDate = LocalDate.of(2026, 7, 28),
        deadline: LocalDateTime = LocalDateTime.of(2026, 7, 29, 0, 0),
    ) = Challenge(
        id = id,
        challengerId = meId,
        opponentId = 7L,
        challengerMission = "운동 1시간",
        opponentMission = if (status == ChallengeStatus.IN_PROGRESS) "책 30페이지" else null,
        betContent = "커피 사기",
        challengeDate = challengeDate,
        deadline = deadline,
        status = status,
        result = null,
        createdAt = LocalDateTime.of(2026, 7, 28, 2, 11, 0),
        completedAt = null,
    )
}
