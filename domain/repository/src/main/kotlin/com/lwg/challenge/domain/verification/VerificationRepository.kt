package com.lwg.challenge.domain.verification

/**
 * Verification 영속성 추상.
 *
 * challengeId+userId 가 UNIQUE.
 */
interface VerificationRepository {

    /**
     * 주어진 challengeId 목록에 속한 모든 verification row 를 한 번에 조회.
     *
     * home-feed 응답에서 N+1 회피용 — 사용자가 가진 진행 중 챌린지 N 개에 대해 한 SELECT 로
     * 양측(나/상대) verification 을 모두 가져온다.
     */
    fun findAllByChallengeIds(challengeIds: Collection<Long>): List<Verification>

    /** 저장 (테스트 시드/인증 트랜잭션용). */
    fun save(verification: Verification): Verification
}
