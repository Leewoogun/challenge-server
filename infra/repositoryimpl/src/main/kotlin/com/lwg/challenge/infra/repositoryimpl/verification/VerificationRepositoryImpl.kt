package com.lwg.challenge.infra.repositoryimpl.verification

import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationRepository
import com.lwg.challenge.infra.entity.verification.VerificationEntity
import com.lwg.challenge.infra.jpa.verification.VerificationJpaRepository
import org.springframework.stereotype.Component

@Component
class VerificationRepositoryImpl(
    private val jpa: VerificationJpaRepository,
) : VerificationRepository {

    override fun findAllByChallengeIds(challengeIds: Collection<Long>): List<Verification> {
        if (challengeIds.isEmpty()) return emptyList()
        return jpa.findAllByChallengeIdIn(challengeIds).map { it.toDomain() }
    }

    override fun save(verification: Verification): Verification =
        jpa.save(VerificationEntity.fromDomain(verification)).toDomain()
}
