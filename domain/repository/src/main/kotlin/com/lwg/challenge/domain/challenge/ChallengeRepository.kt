package com.lwg.challenge.domain.challenge

/**
 * 챌린지 영속성 추상.
 *
 * 구현체는 `:infra:repositoryimpl` 의 `ChallengeRepositoryImpl`.
 */
interface ChallengeRepository {

    /**
     * 특정 사용자가 challenger 이거나 opponent 인 챌린지 중, 상태가 [statuses] 에 속한 것을
     * `deadline` 오름차순으로 반환.
     *
     * home-feed 응답에서 사용. IN_PROGRESS 만 넘기면 진행 중 챌린지 목록이 된다.
     */
    fun findActiveByUser(userId: Long, statuses: Collection<ChallengeStatus>): List<Challenge>

    /** id 로 단건 조회. 없으면 null. (테스트 시드/검증 용) */
    fun findById(id: Long): Challenge?

    /** 저장 (id == null 이면 INSERT, 있으면 MERGE). 테스트 시드 용도 위주. */
    fun save(challenge: Challenge): Challenge
}
