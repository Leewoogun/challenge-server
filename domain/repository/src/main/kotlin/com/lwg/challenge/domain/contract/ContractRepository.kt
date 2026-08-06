package com.lwg.challenge.domain.contract

/**
 * 계약서 영속성 추상 (soul-oath). 구현체는 `:infra:repositoryimpl`.
 */
interface ContractRepository {

    /** 챌린지에 딸린 계약서. 없으면 null. `challenge_id` 가 UNIQUE 라 최대 1건. */
    fun findByChallengeId(challengeId: Long): Contract?

    /** 저장 (id == null 이면 INSERT, 있으면 MERGE). */
    fun save(contract: Contract): Contract

    /**
     * 챌린지에 딸린 계약서를 삭제한다.
     *
     * **취소(물리 삭제) 경로에서 challenge 를 지우기 전에 먼저 호출해야 한다.**
     * `contracts_challenge_id_fkey` 에 `ON DELETE` 절이 없어, 순서를 지키지 않으면
     * FK 위반 → HTTP 500 이 난다 (soul-oath api-contract §0.4).
     *
     * CASCADE 를 쓰지 않는 이유: 이 제품은 "무를 수 없는 약속" 이 컨셉인데 CASCADE 는
     * **다른 이유로 challenge 를 지울 때도 맹세를 조용히 함께 지운다.**
     */
    fun deleteByChallengeId(challengeId: Long)
}
