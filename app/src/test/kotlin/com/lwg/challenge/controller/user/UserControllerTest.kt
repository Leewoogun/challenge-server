package com.lwg.challenge.controller.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.controller.common.exception.GlobalExceptionHandler
import com.lwg.challenge.controller.user.dto.FcmTokenRequest
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.DialogException
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.service.user.UserService
import com.lwg.challenge.service.user.WithdrawalService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/**
 * UserController 슬라이스 테스트.
 *
 * - `@WebMvcTest` 로 컨트롤러 + GlobalExceptionHandler 만 로드 (DB 불필요).
 * - `addFilters=false` 로 SecurityFilterChain 우회. SecurityContext 에 직접 principal=Long 주입.
 * - UserService 는 mock.
 */
@WebMvcTest(controllers = [UserController::class])
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class UserControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockitoBean
    lateinit var userService: UserService

    @MockitoBean
    lateinit var withdrawalService: WithdrawalService

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

    @Test
    fun `getMe - 정상 응답 4 필드`() {
        Mockito.`when`(userService.getMe(meId)).thenReturn(
            User(
                id = meId,
                kakaoId = 4883170475L,
                nickname = "이우건",
                profileImageUrl = "http://img1.kakaocdn.net/u/42.jpg",
                phoneNumber = null,
                phoneVerified = false,
                fcmToken = null,
                status = UserStatus.ACTIVE,
                createdAt = LocalDateTime.now(),
                updatedAt = LocalDateTime.now(),
            ),
        )

        mockMvc.perform(get("/api/v1/users/me"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.id").value(meId))
            .andExpect(jsonPath("$.data.kakaoId").value(4883170475L))
            .andExpect(jsonPath("$.data.nickname").value("이우건"))
            .andExpect(jsonPath("$.data.profileImageUrl").value("http://img1.kakaocdn.net/u/42.jpg"))
    }

    @Test
    fun `getMe - 사용자 부재면 UnauthorizedException → HTTP 401 + code 401`() {
        Mockito.`when`(userService.getMe(meId))
            .thenThrow(UnauthorizedException("user not found"))

        mockMvc.perform(get("/api/v1/users/me"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))
    }

    // ─── PUT /me/fcm-token (push-fcm T-B1) ───────────────────────────────

    /**
     * 응답에 `data` 가 **없다.** 계약 §1 의 shape 이며 `logout` 과 동일하다.
     * 모바일이 `data` 를 non-nullable 로 선언하면 `MissingFieldException` 이 난다 (#25 실패 모드).
     */
    @Test
    fun `updateFcmToken - 정상 등록이면 data 없는 BaseResponse 200`() {
        val body = mockMvc.perform(
            put("/api/v1/users/me/fcm-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fcmToken":"$SAMPLE_TOKEN"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andReturn().response.contentAsString

        // 🔴 응답 **원문**으로 확인한다. jsonPath("$.data").doesNotExist() 만으로는 부족하다 —
        // JsonPath 는 `{"data":null}` 에서도 값을 찾지 못해 doesNotExist 가 **통과해 버린다.**
        // 고정하려는 건 "키가 null" 이 아니라 "키가 아예 없다" 이고, 모바일에서 그 둘은 서로 다른
        // 예외가 된다 (JsonConvertException vs MissingFieldException).
        assertFalse(body.contains("\"data\""), "data 키가 없어야 한다 (계약 §1). 실제 응답=$body")

        Mockito.verify(userService).updateFcmToken(meId, SAMPLE_TOKEN)
    }

    @Test
    fun `updateFcmToken - 토큰이 blank 면 HTTP 200 + code 700`() {
        mockMvc.perform(
            put("/api/v1/users/me/fcm-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fcmToken":"   "}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("FCM 토큰이 비어 있어요"))

        Mockito.verifyNoInteractions(userService)
    }

    /**
     * 🔴 빈 문자열을 "토큰 제거"로 해석하지 않는다. 그렇게 하면 모바일이 토큰 획득에 실패해 빈 값을
     * 보냈을 때 **조용히 알림이 꺼진다.** 제거는 `DELETE /auth/logout` 의 일이다.
     */
    @Test
    fun `updateFcmToken - 빈 문자열도 제거가 아니라 거부다`() {
        mockMvc.perform(
            put("/api/v1/users/me/fcm-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fcmToken":""}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))

        Mockito.verifyNoInteractions(userService)
    }

    @Test
    fun `updateFcmToken - 상한을 넘는 길이는 HTTP 200 + code 700`() {
        val tooLong = "x".repeat(FcmTokenRequest.MAX_LENGTH + 1)

        mockMvc.perform(
            put("/api/v1/users/me/fcm-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("fcmToken" to tooLong))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))

        Mockito.verifyNoInteractions(userService)
    }

    @Test
    fun `updateFcmToken - 사용자 부재면 HTTP 401 + code 401`() {
        Mockito.doThrow(UnauthorizedException("user not found"))
            .`when`(userService).updateFcmToken(meId, SAMPLE_TOKEN)

        mockMvc.perform(
            put("/api/v1/users/me/fcm-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fcmToken":"$SAMPLE_TOKEN"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))
    }

    // ─── DELETE /me (mypage T-B2 — 회원탈퇴) ─────────────────────────────

    /**
     * 응답에 `data` 가 **없다.** `logout` / `fcm-token` 등록과 같은 shape 이다.
     *
     * 🔴 응답 **원문**으로 확인한다 — `jsonPath("$.data").doesNotExist()` 는 `{"data":null}` 에서도
     * 통과해 버린다. 고정하려는 건 "키가 null" 이 아니라 "키가 아예 없다" 이고, 모바일에서 그 둘은
     * 서로 다른 예외가 된다 (위 `updateFcmToken` 테스트와 같은 이유).
     */
    @Test
    fun `withdraw - 정상 탈퇴면 data 없는 BaseResponse 200`() {
        val body = mockMvc.perform(delete("/api/v1/users/me"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andReturn().response.contentAsString

        assertFalse(body.contains("\"data\""), "data 키가 없어야 한다. 실제 응답=$body")

        Mockito.verify(withdrawalService).withdraw(meId)
    }

    /**
     * 🔴 **인증 없이 탈퇴가 실행되면 안 된다.** 슬라이스 테스트라 `addFilters = false` 로
     * SecurityFilterChain 을 우회하므로, 컨트롤러 자신의 `currentUserId()` 가 마지막 방어선이다.
     * 실서비스에서는 그 앞에 `.anyRequest().authenticated()` 가 한 겹 더 있다.
     */
    @Test
    fun `withdraw - principal 이 없으면 HTTP 401 + code 401`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(delete("/api/v1/users/me"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))

        Mockito.verifyNoInteractions(withdrawalService)
    }

    /**
     * 🔴 **탈퇴는 로그아웃과 달리 "언제나 성공" 이어서는 안 된다.** 카카오 연결 해제가 이행되지
     * 않았는데 성공이라고 답하면 사용자는 탈퇴가 끝났다고 믿는데 우리 앱은 여전히 그의 카카오
     * 계정에 연결돼 있다.
     *
     * ⚠️ 실패인데 **HTTP status 는 200** 이다 (700번대 비즈니스 코드 규약). 앱은 `code` 를 보고
     * 갈라야 하며 status 만 봐서는 실패를 감지할 수 없다.
     */
    @Test
    fun `withdraw - 카카오 연결 해제 실패면 HTTP 200 + code 701`() {
        Mockito.doThrow(DialogException("일시적인 문제로 탈퇴하지 못했어요. 잠시 후 다시 시도해주세요"))
            .`when`(withdrawalService).withdraw(meId)

        mockMvc.perform(delete("/api/v1/users/me"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.DIALOG_ERROR))
            .andExpect(jsonPath("$.message").value("일시적인 문제로 탈퇴하지 못했어요. 잠시 후 다시 시도해주세요"))
    }

    private companion object {
        const val SAMPLE_TOKEN = "dGhpcyBpcyBhIGZha2UgdG9rZW4"
    }
}
