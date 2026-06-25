package com.lwg.challenge.controller.friend

import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.controller.common.exception.GlobalExceptionHandler
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.friend.Friend
import com.lwg.challenge.domain.friend.FriendRequest
import com.lwg.challenge.domain.friend.Friendship
import com.lwg.challenge.domain.friend.FriendshipStatus
import com.lwg.challenge.domain.friend.Relation
import com.lwg.challenge.domain.friend.UserSearchResult
import com.lwg.challenge.service.friend.FriendService
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
import java.time.LocalDateTime

/**
 * FriendController 슬라이스 테스트.
 *
 * - `@WebMvcTest` 로 컨트롤러 + GlobalExceptionHandler 만 로드 (DB 불필요).
 * - `addFilters=false` 로 SecurityFilterChain 우회. SecurityContext 에 직접 principal=Long 주입.
 * - FriendService 는 mock — service 비즈니스 로직은 통합 테스트에서 커버.
 *
 * 검증 포인트:
 *  - 7개 endpoint 각각 happy path (200 + data)
 *  - SnackbarException 변환 (HTTP 200 + code 700)
 *  - Path 변수 / Query 변수 / Body 바인딩
 *  - relation enum 직렬화 (UPPER_SNAKE_CASE)
 *  - 빈 배열 응답 (null 금지)
 */
@WebMvcTest(controllers = [FriendController::class])
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class FriendControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockitoBean
    lateinit var friendService: FriendService

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

    // ─── 1. GET /users/search ───────────────────────────────────

    @Test
    fun `searchUsers - 정상 응답 + relation 5종 직렬화`() {
        Mockito.`when`(friendService.searchUsersByNickname(meId, "민수")).thenReturn(
            listOf(
                UserSearchResult(10L, "민수1", null, Relation.NONE, null),
                UserSearchResult(11L, "민수2", "https://cdn/u/11.jpg", Relation.REQUEST_SENT, 901L),
                UserSearchResult(12L, "민수3", null, Relation.REQUEST_RECEIVED, 902L),
                UserSearchResult(13L, "민수4", null, Relation.FRIEND, null),
                UserSearchResult(14L, "민수5", null, Relation.REJECTED, null),
            ),
        )

        mockMvc.perform(get("/api/v1/users/search").param("nickname", "민수"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.users.length()").value(5))
            .andExpect(jsonPath("$.data.users[0].relation").value("NONE"))
            .andExpect(jsonPath("$.data.users[1].relation").value("REQUEST_SENT"))
            .andExpect(jsonPath("$.data.users[1].pendingRequestId").value(901))
            .andExpect(jsonPath("$.data.users[2].relation").value("REQUEST_RECEIVED"))
            .andExpect(jsonPath("$.data.users[2].pendingRequestId").value(902))
            .andExpect(jsonPath("$.data.users[3].relation").value("FRIEND"))
            .andExpect(jsonPath("$.data.users[3].pendingRequestId").doesNotExist())
            .andExpect(jsonPath("$.data.users[4].relation").value("REJECTED"))
            .andExpect(jsonPath("$.data.users[4].pendingRequestId").doesNotExist())
    }

    @Test
    fun `searchUsers - 검색어 2자 미만이면 SnackbarException → code 700`() {
        Mockito.`when`(friendService.searchUsersByNickname(anyLong(), anyString()))
            .thenThrow(SnackbarException("검색어를 2자 이상 입력해주세요"))

        mockMvc.perform(get("/api/v1/users/search").param("nickname", "x"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("검색어를 2자 이상 입력해주세요"))
    }

    @Test
    fun `searchUsers - 결과 0건이면 빈 배열`() {
        Mockito.`when`(friendService.searchUsersByNickname(meId, "없는닉")).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/users/search").param("nickname", "없는닉"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.users").isArray)
            .andExpect(jsonPath("$.data.users.length()").value(0))
    }

    // ─── 2. POST /friends/requests ───────────────────────────────

    @Test
    fun `sendRequest - 정상 응답 PENDING`() {
        Mockito.`when`(friendService.sendRequest(meId, 100L)).thenReturn(
            Friendship(
                id = 5001L,
                requesterId = meId,
                receiverId = 100L,
                status = FriendshipStatus.PENDING,
                createdAt = LocalDateTime.now(),
                acceptedAt = null,
            ),
        )

        mockMvc.perform(
            post("/api/v1/friends/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("receiverId" to 100))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.requestId").value(5001))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
    }

    @Test
    fun `sendRequest - 동일 방향 PENDING이면 SnackbarException → code 700`() {
        Mockito.`when`(friendService.sendRequest(meId, 100L))
            .thenThrow(SnackbarException("이미 요청 보냈습니다"))

        mockMvc.perform(
            post("/api/v1/friends/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("receiverId" to 100))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("이미 요청 보냈습니다"))
    }

    @Test
    fun `sendRequest - receiverId가 0 이하면 validation 실패`() {
        mockMvc.perform(
            post("/api/v1/friends/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("receiverId" to 0))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
    }

    // ─── 3. POST /friends/requests/{id}/accept ───────────────────

    @Test
    fun `acceptRequest - 정상 응답 ACCEPTED`() {
        Mockito.`when`(friendService.acceptRequest(meId, 901L)).thenReturn(
            Friendship(
                id = 901L,
                requesterId = 100L,
                receiverId = meId,
                status = FriendshipStatus.ACCEPTED,
                createdAt = LocalDateTime.now().minusHours(1),
                acceptedAt = LocalDateTime.now(),
            ),
        )

        mockMvc.perform(post("/api/v1/friends/requests/901/accept"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friendshipId").value(901))
            .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
    }

    @Test
    fun `acceptRequest - 권한 없음이면 code 700`() {
        Mockito.`when`(friendService.acceptRequest(meId, 901L))
            .thenThrow(SnackbarException("이 요청을 수락할 권한이 없어요"))

        mockMvc.perform(post("/api/v1/friends/requests/901/accept"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("이 요청을 수락할 권한이 없어요"))
    }

    // ─── 4. POST /friends/requests/{id}/reject ───────────────────

    @Test
    fun `rejectRequest - 정상 응답 REJECTED`() {
        Mockito.`when`(friendService.rejectRequest(meId, 901L)).thenReturn(
            Friendship(
                id = 901L,
                requesterId = 100L,
                receiverId = meId,
                status = FriendshipStatus.REJECTED,
                createdAt = LocalDateTime.now().minusHours(1),
                acceptedAt = null,
            ),
        )

        mockMvc.perform(post("/api/v1/friends/requests/901/reject"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.requestId").value(901))
            .andExpect(jsonPath("$.data.status").value("REJECTED"))
    }

    // ─── 5. DELETE /friends/requests/{id} ────────────────────────

    @Test
    fun `cancelRequest - 정상 응답`() {
        Mockito.doNothing().`when`(friendService).cancelRequest(meId, 901L)

        mockMvc.perform(delete("/api/v1/friends/requests/901"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.requestId").value(901))
    }

    @Test
    fun `cancelRequest - 권한 없음이면 code 700`() {
        Mockito.doThrow(SnackbarException("이 요청을 취소할 권한이 없어요"))
            .`when`(friendService).cancelRequest(meId, 901L)

        mockMvc.perform(delete("/api/v1/friends/requests/901"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("이 요청을 취소할 권한이 없어요"))
    }

    // ─── 6. GET /friends ─────────────────────────────────────────

    @Test
    fun `listFriends - 친구 목록 반환`() {
        Mockito.`when`(friendService.listFriends(meId)).thenReturn(
            listOf(
                Friend(
                    id = 100L,
                    nickname = "민수",
                    profileImageUrl = "https://cdn/u/100.jpg",
                    since = LocalDateTime.of(2026, 6, 20, 12, 34, 56),
                ),
            ),
        )

        mockMvc.perform(get("/api/v1/friends"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friends.length()").value(1))
            .andExpect(jsonPath("$.data.friends[0].id").value(100))
            .andExpect(jsonPath("$.data.friends[0].nickname").value("민수"))
            .andExpect(jsonPath("$.data.friends[0].profileImageUrl").value("https://cdn/u/100.jpg"))
            .andExpect(jsonPath("$.data.friends[0].since").value("2026-06-20T12:34:56Z"))
    }

    @Test
    fun `listFriends - 친구 0건이면 빈 배열`() {
        Mockito.`when`(friendService.listFriends(meId)).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/friends"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friends").isArray)
            .andExpect(jsonPath("$.data.friends.length()").value(0))
    }

    // ─── 7. GET /friends/requests/received ───────────────────────

    @Test
    fun `listReceivedRequests - 받은 요청 목록 반환`() {
        Mockito.`when`(friendService.listReceivedRequests(meId)).thenReturn(
            listOf(
                FriendRequest(
                    id = 901L,
                    fromUserId = 100L,
                    fromUserNickname = "민수",
                    fromUserProfileImageUrl = null,
                    requestedAt = LocalDateTime.of(2026, 6, 24, 18, 0, 0),
                ),
            ),
        )

        mockMvc.perform(get("/api/v1/friends/requests/received"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.requests.length()").value(1))
            .andExpect(jsonPath("$.data.requests[0].id").value(901))
            .andExpect(jsonPath("$.data.requests[0].fromUser.id").value(100))
            .andExpect(jsonPath("$.data.requests[0].fromUser.nickname").value("민수"))
            .andExpect(jsonPath("$.data.requests[0].requestedAt").value("2026-06-24T18:00:00Z"))
    }

    @Test
    fun `listReceivedRequests - 0건이면 빈 배열`() {
        Mockito.`when`(friendService.listReceivedRequests(meId)).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/friends/requests/received"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.requests").isArray)
            .andExpect(jsonPath("$.data.requests.length()").value(0))
    }
}
