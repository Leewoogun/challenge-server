package com.lwg.challenge.infra.jpa.verification

import com.lwg.challenge.infra.entity.verification.VerificationEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * verifications 테이블 CRUD.
 *
 * findAllByChallengeIdIn 은 Spring Data 가 메서드명만 보고 쿼리 생성.
 */
interface VerificationJpaRepository : JpaRepository<VerificationEntity, Long> {
    fun findAllByChallengeIdIn(challengeIds: Collection<Long>): List<VerificationEntity>
}
