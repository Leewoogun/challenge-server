package com.lwg.challenge.infra.repositoryimpl.friendrecord

import com.lwg.challenge.domain.friendrecord.FriendRecord
import com.lwg.challenge.domain.friendrecord.FriendRecordRepository
import com.lwg.challenge.infra.entity.friendrecord.FriendRecordEntity
import com.lwg.challenge.infra.jpa.friendrecord.FriendRecordJpaRepository
import org.springframework.stereotype.Component

@Component
class FriendRecordRepositoryImpl(
    private val jpa: FriendRecordJpaRepository,
) : FriendRecordRepository {

    override fun findByUserIdAndFriendId(userId: Long, friendId: Long): FriendRecord? =
        jpa.findByUserIdAndFriendId(userId, friendId)?.toDomain()

    override fun save(record: FriendRecord): FriendRecord =
        jpa.save(FriendRecordEntity.fromDomain(record)).toDomain()
}
