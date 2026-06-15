package com.lwg.challenge.infra.jpa.challenge

import com.lwg.challenge.infra.entity.challenge.ChallengeEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * challenges 테이블 CRUD. 모듈 외부에 노출되지 않음 (`ChallengeRepositoryImpl` 만 사용).
 */
interface ChallengeJpaRepository : JpaRepository<ChallengeEntity, Long> {

    /**
     * 현재 사용자가 challenger 또는 opponent 이면서 status 가 주어진 집합에 속한 챌린지를
     * deadline 오름차순으로 반환.
     *
     * IN 절은 빈 컬렉션이면 Hibernate 가 빈 결과를 보장하지 않을 수 있어 호출부에서 가드.
     */
    @Query(
        """
        SELECT c
          FROM ChallengeEntity c
         WHERE (c.challengerId = :userId OR c.opponentId = :userId)
           AND c.status IN (:statuses)
         ORDER BY c.deadline ASC
        """,
    )
    fun findActiveByUser(
        @Param("userId") userId: Long,
        @Param("statuses") statuses: Collection<String>,
    ): List<ChallengeEntity>
}
