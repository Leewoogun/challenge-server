package com.lwg.challenge.infra.jpa.challenge

import com.lwg.challenge.infra.entity.challenge.ChallengeEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * challenges 테이블 CRUD. 모듈 외부에 노출되지 않음 (`ChallengeRepositoryImpl` 만 사용).
 *
 * users JOIN 이 필요한 받은 도전장 목록만 native query — 나머지는 JPQL.
 * (`ChallengeEntity` 는 users 와 association 매핑이 없다. 도메인 순수성 유지를 위해 FK 를 Long 으로만 들고 있다.)
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

    /**
     * 최근 판정된 챌린지 — `status = 'COMPLETED' AND completed_at > :since`, `completed_at DESC`.
     *
     * 홈 목록의 결과 카드 (계약 §2.3). [findActiveByUser] 와 **합치지 않은** 이유는
     * `ChallengeRepository.findRecentlyCompletedByUser` KDoc 참조 — 정렬 키가 다르다.
     *
     * `status` 를 파라미터가 아니라 리터럴로 박은 것은 의도다. 이 쿼리는 `COMPLETED` 만
     * 의미가 있다 — `completed_at` 이 채워지는 상태가 그것뿐이라 다른 값을 넣으면
     * **조건이 조용히 빈 결과가 된다.** 넣을 수 없게 만드는 편이 낫다.
     */
    @Query(
        """
        SELECT c
          FROM ChallengeEntity c
         WHERE (c.challengerId = :userId OR c.opponentId = :userId)
           AND c.status = 'COMPLETED'
           AND c.completedAt > :since
         ORDER BY c.completedAt DESC
        """,
    )
    fun findRecentlyCompletedByUser(
        @Param("userId") userId: Long,
        @Param("since") since: LocalDateTime,
    ): List<ChallengeEntity>

    /**
     * 배치 판정 대상 — `status IN (:statuses) AND deadline <= :now`, deadline 오름차순.
     *
     * 사용자 조건이 없는 유일한 쿼리다 (배치는 전 사용자를 본다). `<=` 경계와 "실행 시각이 아니라
     * deadline 기준" 의 근거는 `ChallengeRepository.findDueByStatus` KDoc 참조.
     *
     * `idx_challenges_deadline_status` 가 받는다. IN 절 빈 컬렉션 가드는 `findActiveByUser` 와
     * 같은 이유로 호출부에 있다.
     */
    @Query(
        """
        SELECT c
          FROM ChallengeEntity c
         WHERE c.status IN (:statuses)
           AND c.deadline <= :now
         ORDER BY c.deadline ASC
        """,
    )
    fun findDueByStatus(
        @Param("statuses") statuses: Collection<String>,
        @Param("now") now: LocalDateTime,
    ): List<ChallengeEntity>

    /**
     * 두 사용자 사이의 같은 날짜 챌린지를 **양방향**으로 조회 (중복 신청 차단).
     *
     * 방향을 구분해서 반환하지 않고 row 를 그대로 준다 — 호출부(service)가
     * `challengerId` 를 보고 "내가 건 것" / "상대가 건 것" 을 판정해 메시지를 분기한다.
     */
    @Query(
        """
        SELECT c
          FROM ChallengeEntity c
         WHERE ((c.challengerId = :userA AND c.opponentId = :userB)
             OR (c.challengerId = :userB AND c.opponentId = :userA))
           AND c.challengeDate = :challengeDate
           AND c.status IN (:statuses)
        """,
    )
    fun findConflicting(
        @Param("userA") userA: Long,
        @Param("userB") userB: Long,
        @Param("challengeDate") challengeDate: LocalDate,
        @Param("statuses") statuses: Collection<String>,
    ): List<ChallengeEntity>

    /**
     * 받은 도전장 목록 — `opponent_id = :me AND status = 'PENDING' AND deadline > :now`.
     * challenger 방향으로 users JOIN 하여 닉네임/프로필을 함께 가져온다 (쿼리 1건).
     *
     * `deadline > :now` 가 lazy expiry 필터 — DB 의 status 는 PENDING 인 채로 두고 응답에서만 제외한다.
     * 마감 정각(`deadline = now`)은 아직 유효하므로 `>=` 가 아니라 `>` 를 쓴다
     * (`KstDeadlineCalculator.isExpired` 의 경계 규칙과 일치).
     *
     * native query 인 이유: ChallengeEntity ↔ UserEntity association 매핑이 없어 JPQL join 을 쓸 수 없다.
     * (FriendshipJpaRepository 의 목록 쿼리들과 동일한 사유·스타일.)
     *
     * 반환 컬럼 순서: challenge_id(Long), challenger_id(Long), nickname(String),
     * profile_image_url(String?), challenger_mission(String), bet_content(String),
     * challenge_date(Date), deadline(Timestamp), created_at(Timestamp).
     */
    @Query(
        value = """
            SELECT
              c.id                 AS challenge_id,
              u.id                 AS challenger_id,
              u.nickname           AS nickname,
              u.profile_image_url  AS profile_image_url,
              c.challenger_mission AS challenger_mission,
              c.bet_content        AS bet_content,
              c.challenge_date     AS challenge_date,
              c.deadline           AS deadline,
              c.created_at         AS created_at
            FROM challenges c
            JOIN users u ON u.id = c.challenger_id
            WHERE c.opponent_id = :me
              AND c.status = 'PENDING'
              AND c.deadline > :now
            ORDER BY c.created_at DESC
        """,
        nativeQuery = true,
    )
    fun findReceivedPending(
        @Param("me") me: Long,
        @Param("now") now: LocalDateTime,
    ): List<Array<Any?>>
}
