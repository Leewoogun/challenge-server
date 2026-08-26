package com.lwg.challenge.service.ranking

import com.lwg.challenge.domain.ranking.LoserRankingEntry
import com.lwg.challenge.domain.ranking.LoserRankingRepository
import com.lwg.challenge.domain.ranking.rankedAsLosers
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 개돼지 랭킹(기획 §3.4) 조회.
 *
 * 읽기 전용 단일 트랜잭션 — 쓰기 0건, 이벤트 발행 0건. 서비스가 하는 일은 원재료를 한 번 읽어
 * 도메인 순수 함수에 넘기는 것뿐이고, 정렬·순위·패배율 규칙은 전부 `rankedAsLosers` 에 있다.
 *
 * ## 판정 배치와 동시에 돌아도 안전한 이유
 *
 * `user_stats` 를 **그대로 읽을 뿐** 집계를 다시 세지 않는다. 배치가 같은 시각에 전적을 갱신하고
 * 있어도 이 조회는 스냅샷 한 번을 읽어 줄만 세우므로, 배치 전 값이든 후 값이든 그 자체로
 * 일관된 랭킹이 나온다.
 */
@Service
class RankingService(
    private val loserRankingRepository: LoserRankingRepository,
) {

    @Transactional(readOnly = true)
    fun getLoserRanking(me: Long): List<LoserRankingEntry> =
        loserRankingRepository.findMeAndFriendStats(me).rankedAsLosers(me)
}
