package com.lwg.challenge.controller.friend

import com.lwg.challenge.controller.friend.dto.AcceptFriendRequestData
import com.lwg.challenge.controller.friend.dto.AcceptFriendRequestResponse
import com.lwg.challenge.controller.friend.dto.CancelFriendRequestData
import com.lwg.challenge.controller.friend.dto.CancelFriendRequestResponse
import com.lwg.challenge.controller.friend.dto.FriendItem
import com.lwg.challenge.controller.friend.dto.FriendsData
import com.lwg.challenge.controller.friend.dto.FriendsResponse
import com.lwg.challenge.controller.friend.dto.FromUser
import com.lwg.challenge.controller.friend.dto.ReceivedFriendRequestItem
import com.lwg.challenge.controller.friend.dto.ReceivedFriendRequestsData
import com.lwg.challenge.controller.friend.dto.ReceivedFriendRequestsResponse
import com.lwg.challenge.controller.friend.dto.RejectFriendRequestData
import com.lwg.challenge.controller.friend.dto.RejectFriendRequestResponse
import com.lwg.challenge.controller.friend.dto.SendFriendRequestBody
import com.lwg.challenge.controller.friend.dto.SendFriendRequestData
import com.lwg.challenge.controller.friend.dto.SendFriendRequestResponse
import com.lwg.challenge.controller.friend.dto.UserSearchData
import com.lwg.challenge.controller.friend.dto.UserSearchItem
import com.lwg.challenge.controller.friend.dto.UserSearchResponse
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.domain.friend.Friend
import com.lwg.challenge.domain.friend.FriendRequest
import com.lwg.challenge.domain.friend.Friendship
import com.lwg.challenge.domain.friend.FriendshipStatus
import com.lwg.challenge.domain.friend.UserSearchResult
import com.lwg.challenge.service.friend.FriendService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset

/**
 * 친구 시스템 컨트롤러 (spec-friend-add §5.1, api-contract-friend-add §1-7).
 *
 * 7개 endpoint 모두 Bearer JWT 필요. 인증은 SecurityFilterChain + JwtAuthenticationFilter.
 * 정상 케이스 principal 은 userId(Long).
 *
 * 시간 직렬화: friendships.created_at / accepted_at 은 DB LocalDateTime (timezone 없음). 백엔드는 UTC 로 저장 가정 →
 * Instant 변환 시 ZoneOffset.UTC 적용 (ActiveChallengeController 와 동일 패턴).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Friend", description = "친구 검색 / 요청 / 수락 / 거절 / 취소 / 목록")
class FriendController(
    private val friendService: FriendService,
) {

    @GetMapping("/users/search")
    @Operation(
        summary = "닉네임 contains 검색 (LIMIT 20)",
        description = "본인 제외 + ACTIVE 사용자만. relation 5종 + pendingRequestId derived 반환.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun searchUsers(
        @RequestParam("nickname") nickname: String,
    ): UserSearchResponse {
        val me = currentUserId()
        val results: List<UserSearchResult> = friendService.searchUsersByNickname(me, nickname)
        return UserSearchResponse(
            data = UserSearchData(users = results.map { it.toDto() }),
        )
    }

    @PostMapping("/friends/requests")
    @Operation(
        summary = "친구 요청 보내기",
        description = "분기: 없음→INSERT, 동일방향 REJECTED→UPDATE 재요청, 그 외(PENDING/ACCEPTED/역방향 PENDING/ACCEPTED)→700.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun sendRequest(
        @Valid @RequestBody body: SendFriendRequestBody,
    ): SendFriendRequestResponse {
        val me = currentUserId()
        val saved: Friendship = friendService.sendRequest(me, body.receiverId)
        return SendFriendRequestResponse(
            data = SendFriendRequestData(
                requestId = saved.id ?: error("저장된 friendship 의 id 가 null 일 수 없습니다"),
                status = FriendshipStatus.PENDING,
            ),
        )
    }

    @PostMapping("/friends/requests/{id}/accept")
    @Operation(
        summary = "받은 친구 요청 수락",
        description = "receiver_id=me 만 가능 + status=PENDING 만 가능. ACCEPTED 로 UPDATE + accepted_at=now().",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun acceptRequest(
        @PathVariable("id") id: Long,
    ): AcceptFriendRequestResponse {
        val me = currentUserId()
        val updated: Friendship = friendService.acceptRequest(me, id)
        return AcceptFriendRequestResponse(
            data = AcceptFriendRequestData(
                friendshipId = updated.id ?: error("저장된 friendship 의 id 가 null 일 수 없습니다"),
                status = FriendshipStatus.ACCEPTED,
            ),
        )
    }

    @PostMapping("/friends/requests/{id}/reject")
    @Operation(
        summary = "받은 친구 요청 거절",
        description = "receiver_id=me 만 가능 + status=PENDING 만 가능. row 보존(REJECTED). 재요청은 sendRequest 분기.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun rejectRequest(
        @PathVariable("id") id: Long,
    ): RejectFriendRequestResponse {
        val me = currentUserId()
        val updated: Friendship = friendService.rejectRequest(me, id)
        return RejectFriendRequestResponse(
            data = RejectFriendRequestData(
                requestId = updated.id ?: error("저장된 friendship 의 id 가 null 일 수 없습니다"),
                status = FriendshipStatus.REJECTED,
            ),
        )
    }

    @DeleteMapping("/friends/requests/{id}")
    @Operation(
        summary = "내가 보낸 PENDING 요청 취소(물리 삭제)",
        description = "requester_id=me 만 가능 + status=PENDING 만 가능. row 삭제 후 재요청 가능.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun cancelRequest(
        @PathVariable("id") id: Long,
    ): CancelFriendRequestResponse {
        val me = currentUserId()
        friendService.cancelRequest(me, id)
        return CancelFriendRequestResponse(
            data = CancelFriendRequestData(requestId = id),
        )
    }

    @GetMapping("/friends")
    @Operation(
        summary = "친구 목록 (ACCEPTED)",
        description = "양방향 어느 쪽이든 ACCEPTED 매칭 → 상대편 user. accepted_at DESC.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun listFriends(): FriendsResponse {
        val me = currentUserId()
        val friends: List<Friend> = friendService.listFriends(me)
        return FriendsResponse(
            data = FriendsData(friends = friends.map { it.toDto() }),
        )
    }

    @GetMapping("/friends/requests/received")
    @Operation(
        summary = "받은 친구 요청 목록 (PENDING)",
        description = "receiver_id=me AND status=PENDING. created_at DESC.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun listReceivedRequests(): ReceivedFriendRequestsResponse {
        val me = currentUserId()
        val requests: List<FriendRequest> = friendService.listReceivedRequests(me)
        return ReceivedFriendRequestsResponse(
            data = ReceivedFriendRequestsData(requests = requests.map { it.toDto() }),
        )
    }

    // ─── helpers ─────────────────────────────────────────────────

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

    private fun UserSearchResult.toDto(): UserSearchItem = UserSearchItem(
        id = id,
        nickname = nickname,
        profileImageUrl = profileImageUrl,
        relation = relation,
        pendingRequestId = pendingRequestId,
    )

    private fun Friend.toDto(): FriendItem = FriendItem(
        id = id,
        nickname = nickname,
        profileImageUrl = profileImageUrl,
        since = since.toInstant(ZoneOffset.UTC),
    )

    private fun FriendRequest.toDto(): ReceivedFriendRequestItem = ReceivedFriendRequestItem(
        id = id,
        fromUser = FromUser(
            id = fromUserId,
            nickname = fromUserNickname,
            profileImageUrl = fromUserProfileImageUrl,
        ),
        requestedAt = requestedAt.toInstant(ZoneOffset.UTC),
    )
}
