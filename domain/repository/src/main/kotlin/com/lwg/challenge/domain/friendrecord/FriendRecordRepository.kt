package com.lwg.challenge.domain.friendrecord

/**
 * `friend_records` 영속성 추상 (상대별 전적).
 *
 * `(user_id, friend_id)` 가 UNIQUE 이고 **방향이 있다** — `A→B` 와 `B→A` 는 서로 다른 행이다.
 * 조회도 저장도 방향을 그대로 받는다. 이 포트는 방향을 정규화하지 않는다.
 *
 * row 가 없을 수 있다 — 아직 맞붙은 적 없는 두 사람은 행 자체가 없다. 그 경우 호출부에서
 * [FriendRecord.empty] 로 0 채움 (`UserRecordRepository` 와 같은 규약).
 *
 * 구현체는 `:infra:repositoryimpl` 의 `FriendRecordRepositoryImpl`.
 */
interface FriendRecordRepository {

    /** `(user_id, friend_id)` 로 단건 조회. row 가 없으면 null. 인자 순서가 곧 방향이다. */
    fun findByUserIdAndFriendId(userId: Long, friendId: Long): FriendRecord?

    /** 저장 (`id == null` 이면 INSERT, 있으면 MERGE). */
    fun save(record: FriendRecord): FriendRecord
}
