package com.lwg.challenge.infra.repositoryimpl.ranking

import com.lwg.challenge.domain.ranking.LoserRankingRepository
import com.lwg.challenge.domain.ranking.LoserStat
import com.lwg.challenge.infra.jpa.ranking.LoserRankingJpaRepository
import org.springframework.stereotype.Component

/**
 * `LoserRankingRepository` 의 JPA 구현. native query 의 Object[] row → 도메인 매핑 책임.
 *
 * 컬럼 index 기반 캐스팅이라 컬럼 순서가 JPA 쿼리와 1:1 맞아야 한다
 * ([LoserRankingJpaRepository.findMeAndFriendStats] 참조).
 *
 * 🔴 여기서 정렬하지 않는다 — 순서는 `rankedAsLosers` 가 정한다.
 */
@Component
class LoserRankingRepositoryImpl(
    private val jpa: LoserRankingJpaRepository,
) : LoserRankingRepository {

    override fun findMeAndFriendStats(me: Long): List<LoserStat> {
        return jpa.findMeAndFriendStats(me).map { row ->
            // 컬럼 순서: user_id, nickname, profile_image_url, losses, total_challenges, current_loss_streak
            // 숫자 셋은 SQL COALESCE 로 이미 0 채움이 끝나 있어 null 캐스팅이 필요 없다.
            LoserStat(
                userId = (row[0] as Number).toLong(),
                nickname = row[1] as String,
                profileImageUrl = row[2] as String?,
                losses = (row[3] as Number).toInt(),
                totalChallenges = (row[4] as Number).toInt(),
                currentLossStreak = (row[5] as Number).toInt(),
            )
        }
    }
}
