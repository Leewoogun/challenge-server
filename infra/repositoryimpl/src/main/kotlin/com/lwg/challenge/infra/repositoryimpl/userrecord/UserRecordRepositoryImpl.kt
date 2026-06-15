package com.lwg.challenge.infra.repositoryimpl.userrecord

import com.lwg.challenge.domain.userrecord.UserRecord
import com.lwg.challenge.domain.userrecord.UserRecordRepository
import com.lwg.challenge.infra.entity.userrecord.UserRecordEntity
import com.lwg.challenge.infra.jpa.userrecord.UserRecordJpaRepository
import org.springframework.stereotype.Component

@Component
class UserRecordRepositoryImpl(
    private val jpa: UserRecordJpaRepository,
) : UserRecordRepository {

    override fun findByUserId(userId: Long): UserRecord? =
        jpa.findById(userId).map { it.toDomain() }.orElse(null)

    override fun save(record: UserRecord): UserRecord =
        jpa.save(UserRecordEntity.fromDomain(record)).toDomain()
}
