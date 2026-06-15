package com.lwg.challenge.infra.repositoryimpl.challenge

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.infra.entity.challenge.ChallengeEntity
import com.lwg.challenge.infra.jpa.challenge.ChallengeJpaRepository
import org.springframework.stereotype.Component

@Component
class ChallengeRepositoryImpl(
    private val jpa: ChallengeJpaRepository,
) : ChallengeRepository {

    override fun findActiveByUser(userId: Long, statuses: Collection<ChallengeStatus>): List<Challenge> {
        if (statuses.isEmpty()) return emptyList()
        return jpa.findActiveByUser(userId, statuses.map { it.name }).map { it.toDomain() }
    }

    override fun findById(id: Long): Challenge? =
        jpa.findById(id).map { it.toDomain() }.orElse(null)

    override fun save(challenge: Challenge): Challenge =
        jpa.save(ChallengeEntity.fromDomain(challenge)).toDomain()
}
