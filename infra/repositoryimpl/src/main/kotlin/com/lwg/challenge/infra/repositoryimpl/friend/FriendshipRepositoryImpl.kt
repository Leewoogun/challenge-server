package com.lwg.challenge.infra.repositoryimpl.friend

import com.lwg.challenge.domain.friend.Friend
import com.lwg.challenge.domain.friend.FriendRequest
import com.lwg.challenge.domain.friend.Friendship
import com.lwg.challenge.domain.friend.FriendshipRepository
import com.lwg.challenge.domain.friend.Relation
import com.lwg.challenge.domain.friend.UserSearchResult
import com.lwg.challenge.infra.entity.friend.FriendshipEntity
import com.lwg.challenge.infra.jpa.friend.FriendshipJpaRepository
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.LocalDateTime

/**
 * `FriendshipRepository` 의 JPA 구현. native query 의 Object[] row → 도메인 매핑 책임.
 *
 * search / friends / receivedRequests 는 JPA dialect 가 아닌 SQL 그대로 — column index 기반 캐스팅.
 * 컬럼 순서가 JPA 쿼리와 1:1 맞아야 한다 (JpaRepository 쿼리 정의 참조).
 */
@Component
class FriendshipRepositoryImpl(
    private val jpa: FriendshipJpaRepository,
) : FriendshipRepository {

    override fun searchByNickname(me: Long, escapedPattern: String): List<UserSearchResult> {
        return jpa.searchByNickname(me, escapedPattern).map { row ->
            // 컬럼 순서: id, nickname, profile_image_url, pending_request_id, relation
            val id = (row[0] as Number).toLong()
            val nickname = row[1] as String
            val profileImageUrl = row[2] as String?
            val pendingRowId: Long? = (row[3] as Number?)?.toLong()
            val relationStr = row[4] as String
            val relation = runCatching { Relation.valueOf(relationStr) }.getOrDefault(Relation.NONE)

            // pendingRequestId 는 PENDING 관계일 때만 의미가 있음 (REQUEST_SENT / REQUEST_RECEIVED).
            // CASE 식의 relation 이 다른 값이면 null 로 강제 — 컨트랙트 (api-contract §1) 명시.
            val pendingRequestId: Long? = when (relation) {
                Relation.REQUEST_SENT, Relation.REQUEST_RECEIVED -> pendingRowId
                else -> null
            }

            UserSearchResult(
                id = id,
                nickname = nickname,
                profileImageUrl = profileImageUrl,
                relation = relation,
                pendingRequestId = pendingRequestId,
            )
        }
    }

    override fun findById(id: Long): Friendship? =
        jpa.findById(id).map { it.toDomain() }.orElse(null)

    override fun findBetween(userA: Long, userB: Long): List<Friendship> =
        jpa.findBetween(userA, userB).map { it.toDomain() }

    override fun findFriendsOf(me: Long): List<Friend> {
        return jpa.findFriendsOf(me).map { row ->
            // 컬럼 순서: friend_id, nickname, profile_image_url, accepted_at
            Friend(
                id = (row[0] as Number).toLong(),
                nickname = row[1] as String,
                profileImageUrl = row[2] as String?,
                since = toLocalDateTime(row[3]),
            )
        }
    }

    override fun findReceivedRequestsOf(me: Long): List<FriendRequest> {
        return jpa.findReceivedRequestsOf(me).map { row ->
            // 컬럼 순서: friendship_id, from_user_id, nickname, profile_image_url, created_at
            FriendRequest(
                id = (row[0] as Number).toLong(),
                fromUserId = (row[1] as Number).toLong(),
                fromUserNickname = row[2] as String,
                fromUserProfileImageUrl = row[3] as String?,
                requestedAt = toLocalDateTime(row[4]),
            )
        }
    }

    override fun save(friendship: Friendship): Friendship =
        jpa.save(FriendshipEntity.fromDomain(friendship)).toDomain()

    override fun deleteById(id: Long) {
        jpa.deleteById(id)
    }

    private fun toLocalDateTime(value: Any?): LocalDateTime = when (value) {
        is LocalDateTime -> value
        is Timestamp -> value.toLocalDateTime()
        else -> error("기대하지 않은 시간 타입: ${value?.javaClass}")
    }
}
