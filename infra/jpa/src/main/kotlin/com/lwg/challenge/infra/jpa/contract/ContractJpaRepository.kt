package com.lwg.challenge.infra.jpa.contract

import com.lwg.challenge.infra.entity.contract.ContractEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * contracts 테이블 CRUD. 모듈 외부에 노출되지 않음 (`ContractRepositoryImpl` 만 사용).
 */
interface ContractJpaRepository : JpaRepository<ContractEntity, Long> {
    fun findByChallengeId(challengeId: Long): ContractEntity?
    fun deleteByChallengeId(challengeId: Long)
}
