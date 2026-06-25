package com.lwg.challenge.service.friend

import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.friend.Friend
import com.lwg.challenge.domain.friend.FriendRequest
import com.lwg.challenge.domain.friend.Friendship
import com.lwg.challenge.domain.friend.FriendshipRepository
import com.lwg.challenge.domain.friend.FriendshipStatus
import com.lwg.challenge.domain.friend.UserSearchResult
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.user.UserStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 친구 시스템 비즈니스 로직 (spec-friend-add §5).
 *
 * - 검색은 `FriendshipRepository.searchByNickname` 단일 쿼리에 위임. service 는 LIKE escape + min 길이 가드만.
 * - mutation 분기 (sendRequest / acceptRequest / rejectRequest / cancelRequest) 의 권한·상태 검증은 모두 service.
 *   비즈니스 에러는 `SnackbarException` 으로 throw → GlobalExceptionHandler 가 HTTP 200 + code 700 으로 변환.
 * - V1 friendships 스키마 그대로 사용. 마이그레이션 0건.
 */
@Service
class FriendService(
    private val friendshipRepository: FriendshipRepository,
    private val userRepository: UserRepository,
) {

    // ─── 검색 ────────────────────────────────────────────────────

    /**
     * 닉네임 contains 검색. 최소 2자 + 본인 제외 + ACTIVE 사용자 + LIMIT 20.
     *
     * trim 후 길이 검증 → 와일드카드 escape (`\` 먼저, 그 다음 `%`, `_`) → `%pattern%` 으로 감싸 repository 호출.
     */
    @Transactional(readOnly = true)
    fun searchUsersByNickname(me: Long, query: String): List<UserSearchResult> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) {
            throw SnackbarException("검색어를 ${MIN_QUERY_LENGTH}자 이상 입력해주세요")
        }
        val escaped = escapeForLike(trimmed)
        return friendshipRepository.searchByNickname(me = me, escapedPattern = "%$escaped%")
    }

    // ─── 친구 요청 보내기 ────────────────────────────────────────

    /**
     * 친구 요청 보내기. spec §5.3 5종 분기 + 자기 자신 / 미존재 / INACTIVE 가드.
     *
     * 트랜잭션 1건. UNIQUE(requester, receiver) catch 는 race 백업 (사전 검사 통과 후 동시 INSERT).
     */
    @Transactional
    fun sendRequest(me: Long, receiverId: Long): Friendship {
        if (me == receiverId) {
            throw SnackbarException("자기 자신에게는 요청할 수 없어요")
        }

        val receiver = userRepository.findById(receiverId)
            ?: throw SnackbarException("사용자를 찾을 수 없어요")
        if (receiver.status != UserStatus.ACTIVE) {
            throw SnackbarException("사용자를 찾을 수 없어요")
        }

        // 양방향 사전 검사 — (me→target) 와 (target→me) 모두 본다.
        val existing = friendshipRepository.findBetween(me, receiverId)
        val sameDirection = existing.firstOrNull { it.requesterId == me && it.receiverId == receiverId }
        val reverseDirection = existing.firstOrNull { it.requesterId == receiverId && it.receiverId == me }

        // 동일 방향 분기 (REJECTED 만 UPDATE — 나머지는 차단)
        sameDirection?.let { row ->
            when (row.status) {
                FriendshipStatus.PENDING -> throw SnackbarException("이미 요청 보냈습니다")
                FriendshipStatus.ACCEPTED -> throw SnackbarException("이미 친구입니다")
                FriendshipStatus.REJECTED -> {
                    // 동일 row 를 PENDING 으로 UPDATE. created_at 보존 (Entity.updatable=false 로 보장).
                    val updated = row.copy(
                        status = FriendshipStatus.PENDING,
                        acceptedAt = null,
                    )
                    return friendshipRepository.save(updated)
                }
                FriendshipStatus.BLOCKED -> throw SnackbarException("요청을 보낼 수 없는 사용자입니다")
            }
        }

        // 반대 방향 분기 (PENDING 이면 race 안내, ACCEPTED 면 이미 친구)
        reverseDirection?.let { row ->
            when (row.status) {
                FriendshipStatus.PENDING -> throw SnackbarException("상대가 이미 친구 요청을 보냈어요. 확인해보세요")
                FriendshipStatus.ACCEPTED -> throw SnackbarException("이미 친구입니다")
                FriendshipStatus.REJECTED -> {
                    // 상대가 나에게 보낸 요청을 거절한 상태 — 내가 다시 시도 가능 (새 row).
                    // 아무 분기 없이 아래 INSERT 로 진행.
                }
                FriendshipStatus.BLOCKED -> throw SnackbarException("요청을 보낼 수 없는 사용자입니다")
            }
        }

        // 신규 INSERT (status=PENDING). 동시 INSERT race 는 UNIQUE 제약이 백업.
        // saveAndFlush 로 즉시 flush — UNIQUE(requester_id, receiver_id) 위반이 try/catch 안에서
        // DataIntegrityViolationException 으로 잡힌다. save 만 호출하면 flush 가 트랜잭션 commit
        // 시점으로 미뤄져 catch 블록 밖에서 터지므로 500 으로 변환된다.
        val now = LocalDateTime.now()
        val toInsert = Friendship(
            id = null,
            requesterId = me,
            receiverId = receiverId,
            status = FriendshipStatus.PENDING,
            createdAt = now,
            acceptedAt = null,
        )
        return try {
            friendshipRepository.saveAndFlush(toInsert)
        } catch (e: DataIntegrityViolationException) {
            // 사전 검사 통과 후 동시 INSERT 가 부딪힌 경우.
            throw SnackbarException("이미 요청 보냈습니다")
        }
    }

    // ─── 수락 ────────────────────────────────────────────────────

    /**
     * 받은 친구 요청 수락. `receiver_id = me` 만 가능 + `status = PENDING` 만 가능.
     */
    @Transactional
    fun acceptRequest(me: Long, requestId: Long): Friendship {
        val row = friendshipRepository.findById(requestId)
            ?: throw SnackbarException("요청을 찾을 수 없어요")
        if (row.receiverId != me) {
            throw SnackbarException("이 요청을 수락할 권한이 없어요")
        }
        if (row.status != FriendshipStatus.PENDING) {
            throw SnackbarException("이미 처리된 요청입니다")
        }
        val updated = row.copy(
            status = FriendshipStatus.ACCEPTED,
            acceptedAt = LocalDateTime.now(),
        )
        return friendshipRepository.save(updated)
    }

    // ─── 거절 ────────────────────────────────────────────────────

    /**
     * 받은 친구 요청 거절. `receiver_id = me` 만 가능 + `status = PENDING` 만 가능.
     * row 보존 (REJECTED 로 UPDATE) — 재요청 시 sendRequest 가 동일 row 를 PENDING 으로 되돌린다.
     */
    @Transactional
    fun rejectRequest(me: Long, requestId: Long): Friendship {
        val row = friendshipRepository.findById(requestId)
            ?: throw SnackbarException("요청을 찾을 수 없어요")
        if (row.receiverId != me) {
            throw SnackbarException("이 요청을 거절할 권한이 없어요")
        }
        if (row.status != FriendshipStatus.PENDING) {
            throw SnackbarException("이미 처리된 요청입니다")
        }
        val updated = row.copy(
            status = FriendshipStatus.REJECTED,
            acceptedAt = null,
        )
        return friendshipRepository.save(updated)
    }

    // ─── 취소(보낸 요청 회수) ────────────────────────────────────

    /**
     * 내가 보낸 PENDING 요청을 취소. `requester_id = me` 만 가능 + `status = PENDING` 만 가능.
     * 물리 삭제 — 재요청 가능.
     */
    @Transactional
    fun cancelRequest(me: Long, requestId: Long) {
        val row = friendshipRepository.findById(requestId)
            ?: throw SnackbarException("요청을 찾을 수 없어요")
        if (row.requesterId != me) {
            throw SnackbarException("이 요청을 취소할 권한이 없어요")
        }
        if (row.status != FriendshipStatus.PENDING) {
            throw SnackbarException("이미 처리된 요청입니다")
        }
        val rowId = row.id ?: error("영속 friendships row 의 id 가 null 일 수 없습니다")
        friendshipRepository.deleteById(rowId)
    }

    // ─── 목록 조회 ────────────────────────────────────────────────

    /** 친구 목록 (ACCEPTED). `accepted_at DESC` 정렬. */
    @Transactional(readOnly = true)
    fun listFriends(me: Long): List<Friend> = friendshipRepository.findFriendsOf(me)

    /** 받은 요청 목록 (PENDING). `created_at DESC` 정렬. */
    @Transactional(readOnly = true)
    fun listReceivedRequests(me: Long): List<FriendRequest> =
        friendshipRepository.findReceivedRequestsOf(me)

    companion object {
        const val MIN_QUERY_LENGTH = 2

        /**
         * LIKE 와일드카드 escape. `\` 먼저 처리하지 않으면 뒤에 추가하는 escape 가 중첩 escape 됨.
         * ESCAPE '\' 절과 짝을 이루어야 함.
         */
        fun escapeForLike(input: String): String =
            input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
    }
}
