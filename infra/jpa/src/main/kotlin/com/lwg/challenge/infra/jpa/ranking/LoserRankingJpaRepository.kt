package com.lwg.challenge.infra.jpa.ranking

import com.lwg.challenge.infra.entity.auth.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 개돼지 랭킹 원재료 조회. 모듈 외부에 노출되지 않음 (`LoserRankingRepositoryImpl` 만 사용).
 *
 * `users` 를 루트로 잡기 때문에 `JpaRepository<UserEntity, Long>` 에 얹었지만, 이 인터페이스가
 * 쓰는 건 아래 native query 하나뿐이다 — 엔티티 CRUD 용도가 아니다.
 */
interface LoserRankingJpaRepository : JpaRepository<UserEntity, Long> {

    /**
     * 나 + accepted 친구의 전적 원재료를 **한 번의 쿼리로** 읽는다.
     *
     * ## 🔴 `LEFT JOIN user_stats` 인 이유
     *
     * 챌린지를 한 번도 안 한 친구는 `user_stats` row 자체가 없다. INNER JOIN 이면 그 친구가
     * **목록에서 통째로 사라진다** — 계약이 "빠지지 않는다" 로 확정한 지점이다. `COALESCE` 가
     * 0 폴백이고, 이 0 은 `GET /record` 의 `UserRecord.empty` 와 **같은 값 정의**다.
     *
     * ## 왜 친구 목록을 먼저 읽고 N번 조회하지 않나
     *
     * 그건 N+1 이다. 친구 수만큼 쿼리가 는다. 친구 관계 판정(`friendships` 서브쿼리)까지
     * 이 한 문장 안에 있다.
     *
     * ## 왜 `ORDER BY` 가 없나
     *
     * 정렬 규칙은 도메인 순수 함수 `rankedAsLosers` 에 있다. 두 곳에 두면 갈린다.
     *
     * ## ⚠️ `u.status = 'ACTIVE'` 필터를 넣지 않았다
     *
     * 친구 목록 쿼리(`FriendshipJpaRepository.findFriendsOf`)도 넣지 않는다. 여기서만 걸면
     * **친구 목록에는 보이는데 랭킹에서는 사라지는** 불일치가 생긴다. 두 목록의 범위는 같아야 한다.
     *
     * 반환 컬럼: user_id(Long), nickname(String), profile_image_url(String?),
     * losses(Int), total_challenges(Int), current_loss_streak(Int).
     */
    @Query(
        value = """
            SELECT
              u.id                       AS user_id,
              u.nickname                 AS nickname,
              u.profile_image_url        AS profile_image_url,
              COALESCE(us.losses, 0)              AS losses,
              COALESCE(us.total_challenges, 0)    AS total_challenges,
              COALESCE(us.current_loss_streak, 0) AS current_loss_streak
            FROM users u
            LEFT JOIN user_stats us ON us.user_id = u.id
            WHERE u.id = :me
               OR u.id IN (
                 SELECT CASE WHEN f.requester_id = :me THEN f.receiver_id ELSE f.requester_id END
                   FROM friendships f
                  WHERE f.status = 'ACCEPTED'
                    AND (f.requester_id = :me OR f.receiver_id = :me)
               )
        """,
        nativeQuery = true,
    )
    fun findMeAndFriendStats(@Param("me") me: Long): List<Array<Any?>>
}
