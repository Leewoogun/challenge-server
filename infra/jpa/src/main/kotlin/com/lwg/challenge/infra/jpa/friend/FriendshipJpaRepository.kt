package com.lwg.challenge.infra.jpa.friend

import com.lwg.challenge.infra.entity.friend.FriendshipEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * `friendships` 테이블 CRUD. 모듈 외부에 노출되지 않음 (`FriendshipRepositoryImpl` 만 사용).
 *
 * 검색 / 친구 목록 / 받은 요청 목록은 native query 로 작성 — users JOIN + derived 컬럼이 필요하다.
 * 단순 CRUD 와 단건 조회는 Spring Data 메서드명 또는 JpaRepository 기본 제공.
 */
interface FriendshipJpaRepository : JpaRepository<FriendshipEntity, Long> {

    fun findByRequesterIdAndReceiverId(requesterId: Long, receiverId: Long): FriendshipEntity?

    /**
     * 두 사용자 사이의 row 양방향 조회 — `(a→b) OR (b→a)`. 최대 2건.
     * sendRequest 분기에서 race 백업용으로 사용.
     */
    @Query(
        """
        SELECT f
          FROM FriendshipEntity f
         WHERE (f.requesterId = :userA AND f.receiverId = :userB)
            OR (f.requesterId = :userB AND f.receiverId = :userA)
        """,
    )
    fun findBetween(@Param("userA") userA: Long, @Param("userB") userB: Long): List<FriendshipEntity>

    /**
     * 검색 쿼리 (spec-friend-add §5.2). 단일 LEFT JOIN + CASE 식으로 relation derived.
     *
     * native query 인 이유:
     *  - LEFT JOIN ... ON ((req=me AND recv=u.id) OR (recv=me AND req=u.id)) 와 같은 복합 ON 조건
     *  - CASE 식 + ESCAPE 절
     *  - PostgreSQL LIKE 사용 (test 도 동일 Postgres Testcontainers)
     *
     * `:escapedPattern` 은 호출자가 `'%' || escapedQuery || '%'` 형태로 만들어 넘긴다.
     * 와일드카드 `%`, `_`, `\` 는 service 단계에서 `\%`, `\_`, `\\` 로 escape 되어 있어야 함.
     *
     * 정렬: nickname ASC, id ASC. LIMIT 20 고정 (페이지네이션 없음).
     *
     * 반환: Object[] 배열. 컬럼 순서 — id(Long), nickname(String), profile_image_url(String?),
     * pending_request_id(Long?), relation(String).
     */
    @Query(
        value = """
            SELECT
              u.id                AS id,
              u.nickname          AS nickname,
              u.profile_image_url AS profile_image_url,
              f.id                AS pending_request_id,
              CASE
                WHEN f.status = 'ACCEPTED' THEN 'FRIEND'
                WHEN f.requester_id = :me AND f.status = 'PENDING'  THEN 'REQUEST_SENT'
                WHEN f.receiver_id  = :me AND f.status = 'PENDING'  THEN 'REQUEST_RECEIVED'
                WHEN f.requester_id = :me AND f.status = 'REJECTED' THEN 'REJECTED'
                ELSE 'NONE'
              END AS relation
            FROM users u
            LEFT JOIN friendships f
              ON ((f.requester_id = :me AND f.receiver_id = u.id)
               OR (f.receiver_id  = :me AND f.requester_id = u.id))
            WHERE u.id <> :me
              AND u.nickname LIKE :escapedPattern ESCAPE '\'
              AND u.status = 'ACTIVE'
            ORDER BY u.nickname ASC, u.id ASC
            LIMIT 20
        """,
        nativeQuery = true,
    )
    fun searchByNickname(
        @Param("me") me: Long,
        @Param("escapedPattern") escapedPattern: String,
    ): List<Array<Any?>>

    /**
     * 친구 목록(ACCEPTED). 양방향 어느 쪽이든 ACCEPTED 매칭.
     * "상대편" user 정보를 매핑해 반환.
     *
     * 반환 컬럼: friend_id(Long), nickname(String), profile_image_url(String?), accepted_at(Timestamp).
     */
    @Query(
        value = """
            SELECT
              CASE WHEN f.requester_id = :me THEN f.receiver_id ELSE f.requester_id END AS friend_id,
              u.nickname,
              u.profile_image_url,
              f.accepted_at
            FROM friendships f
            JOIN users u
              ON u.id = CASE WHEN f.requester_id = :me THEN f.receiver_id ELSE f.requester_id END
            WHERE f.status = 'ACCEPTED'
              AND (f.requester_id = :me OR f.receiver_id = :me)
            ORDER BY f.accepted_at DESC
        """,
        nativeQuery = true,
    )
    fun findFriendsOf(@Param("me") me: Long): List<Array<Any?>>

    /**
     * 받은 요청 목록(PENDING). `receiver_id = me AND status = 'PENDING'` + requester users JOIN.
     *
     * 반환 컬럼: friendship_id(Long), from_user_id(Long), nickname(String),
     * profile_image_url(String?), created_at(Timestamp).
     */
    @Query(
        value = """
            SELECT
              f.id          AS friendship_id,
              u.id          AS from_user_id,
              u.nickname    AS nickname,
              u.profile_image_url AS profile_image_url,
              f.created_at  AS created_at
            FROM friendships f
            JOIN users u ON u.id = f.requester_id
            WHERE f.receiver_id = :me
              AND f.status = 'PENDING'
            ORDER BY f.created_at DESC
        """,
        nativeQuery = true,
    )
    fun findReceivedRequestsOf(@Param("me") me: Long): List<Array<Any?>>
}
