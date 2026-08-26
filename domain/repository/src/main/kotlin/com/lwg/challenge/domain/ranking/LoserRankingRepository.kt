package com.lwg.challenge.domain.ranking

/**
 * 개돼지 랭킹 원재료 조회 포트. 읽기 전용이라 save/delete 가 없다.
 *
 * 구현체는 `:infra:repositoryimpl` 의 `LoserRankingRepositoryImpl`.
 */
interface LoserRankingRepository {

    /**
     * 나 + accepted 친구의 전적 원재료를 한 번에 읽는다. 정렬·rank 부여는 하지 않는다 —
     * 그건 [rankedAsLosers] 의 책임이고, 두 곳에 두면 규칙이 갈린다.
     *
     * `user_stats` row 가 없는 사용자(챌린지 미경험자)는 0 으로 채워져 온다. 즉 반환 목록에서
     * 빠지지 않으며, 최소 1건(나)은 항상 있다.
     */
    fun findMeAndFriendStats(me: Long): List<LoserStat>
}
