package com.lwg.challenge.infra.repositoryimpl.contract

import com.lwg.challenge.domain.contract.Contract
import com.lwg.challenge.domain.contract.ContractRepository
import com.lwg.challenge.infra.entity.contract.ContractEntity
import com.lwg.challenge.infra.jpa.contract.ContractJpaRepository
import org.springframework.stereotype.Component

@Component
class ContractRepositoryImpl(
    private val jpa: ContractJpaRepository,
) : ContractRepository {

    override fun findByChallengeId(challengeId: Long): Contract? =
        jpa.findByChallengeId(challengeId)?.toDomain()

    override fun save(contract: Contract): Contract =
        jpa.save(ContractEntity.fromDomain(contract)).toDomain()

    override fun deleteByChallengeId(challengeId: Long) {
        jpa.deleteByChallengeId(challengeId)
    }
}
