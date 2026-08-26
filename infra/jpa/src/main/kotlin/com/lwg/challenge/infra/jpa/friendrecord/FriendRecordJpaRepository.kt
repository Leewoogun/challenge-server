package com.lwg.challenge.infra.jpa.friendrecord

import com.lwg.challenge.infra.entity.friendrecord.FriendRecordEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * friend_records 테이블 CRUD. 모듈 외부에 노출되지 않음 (`FriendRecordRepositoryImpl` 만 사용).
 *
 * PK 가 `id`(BIGSERIAL) 라 `findById` 로는 쓸 수 없다 — 실제 조회 키는 `(user_id, friend_id)`
 * 이므로 파생 쿼리 하나가 필요하다. `user_stats` 가 `JpaRepository` 만으로 충분했던 것과 다른 점.
 */
interface FriendRecordJpaRepository : JpaRepository<FriendRecordEntity, Long> {

    /** `uq_friend_records_user_friend` 가 유일성을 보장하므로 단건이다. 인자 순서가 곧 방향이다. */
    fun findByUserIdAndFriendId(userId: Long, friendId: Long): FriendRecordEntity?
}
