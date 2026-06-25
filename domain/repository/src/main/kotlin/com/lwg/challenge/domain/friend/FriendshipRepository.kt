package com.lwg.challenge.domain.friend

/**
 * `friendships` 영속성 추상. 구현체는 `:infra:repositoryimpl` 의 `FriendshipRepositoryImpl`.
 *
 * 친구 검색(닉네임 contains)은 `users LEFT JOIN friendships` 단일 쿼리로 derived `Relation` /
 * `pendingRequestId` 를 함께 계산해 반환한다 (spec-friend-add §5.2). 구현은 native query.
 */
interface FriendshipRepository {

    /**
     * 닉네임 contains 검색. 본인 제외 + ACTIVE 사용자만 + LIMIT 20 + ORDER BY u.nickname ASC, u.id ASC.
     *
     * @param me              조회자(현재 로그인 user id)
     * @param escapedPattern  서비스 계층에서 LIKE escape 가 끝난 패턴 — `'%' || escapedQuery || '%'` 형식.
     *                        와일드카드(`%`, `_`, `\`) 가 escape 되어 있어야 함. `ESCAPE '\'` 절을 함께 사용.
     * @return derived 결과 (최대 20건)
     */
    fun searchByNickname(me: Long, escapedPattern: String): List<UserSearchResult>

    /**
     * id 단건 조회.
     */
    fun findById(id: Long): Friendship?

    /**
     * 두 사용자 사이의 friendships row (어느 방향이든) 단건 조회.
     *
     * UNIQUE(requester_id, receiver_id) 이지만 (a→b) 와 (b→a) 가 동시에 존재할 수 있다 (race 백업).
     * sendRequest 분기에서는 사전 검사로 둘 다 봐야 하므로 이 메서드를 사용.
     *
     * @return (a→b) 그리고/또는 (b→a) row 목록 (최대 2건)
     */
    fun findBetween(userA: Long, userB: Long): List<Friendship>

    /**
     * 친구 목록(ACCEPTED). 양방향 어느 쪽이든 ACCEPTED 매칭 → "상대편" user 정보를 매핑해 반환.
     * 정렬: `accepted_at DESC`.
     */
    fun findFriendsOf(me: Long): List<Friend>

    /**
     * 받은 요청 목록(PENDING). `receiver_id = me AND status = 'PENDING'` + requester users JOIN.
     * 정렬: `created_at DESC`.
     */
    fun findReceivedRequestsOf(me: Long): List<FriendRequest>

    /**
     * Friendship row 저장.
     * - `id == null` ⇒ INSERT.
     * - `id != null` ⇒ MERGE (status / accepted_at 갱신, created_at 보존).
     */
    fun save(friendship: Friendship): Friendship

    /**
     * Friendship row 저장 후 즉시 flush.
     *
     * sendRequest 신규 INSERT 분기에서 사용. `save` 만 호출하면 EntityManager.persist 만 수행되고
     * UNIQUE(requester_id, receiver_id) 제약 위반은 트랜잭션 commit 시점에 발생 → service 의 try/catch 밖.
     * race 시 의도한 `SnackbarException("이미 요청 보냈습니다")` 가 아니라 500 으로 노출되는 문제 방지.
     */
    fun saveAndFlush(friendship: Friendship): Friendship

    /**
     * row 물리 삭제 — 요청 취소(cancelRequest)에서만 사용.
     */
    fun deleteById(id: Long)
}
