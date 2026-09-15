package com.lwg.challenge.infra.jpa.challenge

import com.lwg.challenge.infra.entity.challenge.ChallengeEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
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
     * 잠금 단건 조회 — **기권 전용** (challenge-forfeit T-B3).
     *
     * ⚠️ 실제로 나가는 SQL 은 `... where ce1_0.id=? for no key update` 다 —
     * Hibernate 6 PostgreSQL 방언의 `PESSIMISTIC_WRITE` 렌더링이다(실구동 로그 실측).
     *
     * 🔴 `findById` 를 오버라이드하지 않고 **다른 이름의 메서드**로 둔 것이 의도다.
     * 오버라이드하면 이 레포의 **모든 조회가 행을 잠그게 되어** 조회끼리 서로를 막는다.
     * 잠금이 필요한 곳만 이 이름을 부른다.
     *
     * 왜 잠가야 하는지는 `ChallengeRepository.findByIdForUpdate` KDoc 참조 —
     * 양쪽 당사자가 동시에 기권을 눌렀을 때 전적이 두 번 세어지는 경로를 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ChallengeEntity c WHERE c.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): ChallengeEntity?

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
     * 계약서 보관함 — **한 달치** `status = 'COMPLETED'` 를 `challenge_date DESC, id DESC` 로 반환
     * (mypage T-B1, 2026-08-28 월 단위 개정).
     *
     * ## 🔴 범위가 반개구간이다 — `>= :monthStart AND < :nextMonthStart`
     *
     * `BETWEEN` 이나 `<= 말일` 을 쓰지 않는다. 말일을 계산하려면 2월·윤년을 다루는 분기가
     * 하나 생기는데, 반개구간은 **"다음 달 1일"** 하나만 알면 되고 그건
     * `YearMonth.plusMonths(1).atDay(1)` 로 분기 없이 나온다. 경계 버그가 생길 자리를 없앤다.
     *
     * ## 🔴 정렬·범위 키가 `completed_at` 이 아니라 `challenge_date` 다
     *
     * [findRecentlyCompletedByUser] 와 **의도적으로 다르다.** 판정이 자정 직후 배치라
     * 7월 31일 챌린지의 `completed_at` 은 8월 1일 00:05 다. `completed_at` 으로 범위를 자르면
     * **7월 마지막 날 챌린지가 8월 응답에 들어간다** — 카드에 적힌 날짜(7/31)와 화면 상단의
     * 월 표시(8월)가 한 화면에서 다른 말을 하게 된다. 홈 목록이 `completed_at DESC` 인 것은
     * *"판정이 방금 났다"* 를 보여주는 다른 목적이라 서로 옳다.
     *
     * 2차 키가 `id DESC` 인 이유: `challenge_date` 는 날짜라 같은 날 여러 건이 가능하다.
     * 동률에서 순서가 흔들리면 같은 목록을 두 번 불러올 때 카드 순서가 바뀐다.
     *
     * `status` 를 리터럴로 박은 것은 [findRecentlyCompletedByUser] 와 같은 판단이다 —
     * 보관함은 `COMPLETED` 만 의미가 있고, 파라미터로 열어 두면 다른 값이 들어와
     * **조용히 빈 결과가 된다.** (`EXPIRED` 를 제외하는 근거는
     * `ChallengeRepository.findCompletedHistoryByUserInMonth` KDoc.)
     *
     * @param monthStart 조회 월의 1일 (포함)
     * @param nextMonthStart 다음 달 1일 (**미포함**)
     */
    @Query(
        """
        SELECT c
          FROM ChallengeEntity c
         WHERE (c.challengerId = :userId OR c.opponentId = :userId)
           AND c.status = 'COMPLETED'
           AND c.challengeDate >= :monthStart
           AND c.challengeDate < :nextMonthStart
         ORDER BY c.challengeDate DESC, c.id DESC
        """,
    )
    fun findCompletedHistoryByUserInMonth(
        @Param("userId") userId: Long,
        @Param("monthStart") monthStart: LocalDate,
        @Param("nextMonthStart") nextMonthStart: LocalDate,
    ): List<ChallengeEntity>

    /**
     * 보관함의 **과거 하한** — 이 사용자의 가장 오래된 `COMPLETED` 챌린지 날짜. 없으면 null.
     *
     * 앱의 `<` 화살표 비활성 판정에 쓴다. 이 날짜의 월보다 이전은 **0건인 것이 보장**되므로
     * 그 아래로 걸어 들어가게 두면 *"더 뒤에 있을지도"* 라는 거짓 기대를 만든다
     * (`ChallengeRepository.findEarliestCompletedChallengeDate` KDoc).
     *
     * `MIN` 을 쓰는 이유: `EXISTS(... < :monthStart)` 로도 불리언은 나오지만, 같은 인덱스
     * 스캔으로 **가장 오래된 달 자체**까지 얻을 수 있다. 계약이 그 값을 노출하게 되면
     * 쿼리를 다시 설계하지 않아도 된다.
     *
     * 범위 조건이 없다 — 하한은 조회 중인 달과 무관한 사용자 단위 상수다.
     */
    @Query(
        """
        SELECT MIN(c.challengeDate)
          FROM ChallengeEntity c
         WHERE (c.challengerId = :userId OR c.opponentId = :userId)
           AND c.status = 'COMPLETED'
        """,
    )
    fun findEarliestCompletedChallengeDate(@Param("userId") userId: Long): LocalDate?

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
     * ## 🔴 이 WHERE 절은 [findPendingReceivedIdsIn] 과 **같아야 한다**
     *
     * 저쪽은 같은 도메인 규칙(*"이 사용자가 아직 응답을 기다리는 신청"*)의 **두 번째 구현**이다 —
     * 여기는 홈 '받은 도전장' 목록으로, 저기는 알림의 `referencePending` 건별 불리언으로 답할 뿐
     * 묻는 질문이 같다. **이 세 조건 중 하나라도 고칠 일이 생기면 저쪽도 같이 고쳐라.**
     *
     * 어긋나면 나는 증상: *"알림은 탭되는데 홈 '받은 도전장'엔 그 카드가 없다"* 또는 그 반대.
     * 🔴 **조용히 틀린다** — 두 쿼리 다 성공하고 응답도 200 이라 예외도 로그도 남지 않는다.
     *
     * ⚠️ 특히 **`deadline > :now` 를 빼지 마라.** 위의 lazy expiry 때문에 마감이 지난 row 도
     * `status = 'PENDING'` 인 채로 정상적으로 존재한다 — 조건을 빼면 **만료된 신청이 "대기 중"으로
     * 나간다.** 그건 `referencePending` 이 애초에 고치려던 버그의 재발이다.
     *
     * **하나로 합치지 않은 이유**: 이쪽은 native 고 저쪽은 JPQL 이라 컬럼 표기부터 다르고
     * (`c.opponent_id` vs `c.opponentId`), SELECT 절·JOIN·정렬도 다르다. 합치려면 둘 중 하나를
     * 상대 쪽 형태로 끌고 와야 하는데 그 편이 더 위험하다. 대신
     * **`ChallengeReceivedPredicateBindingTest` 가 두 쿼리 문자열을 묶어 놓는다** — 한쪽에서
     * 조건이 빠지면 그 테스트가 깨진다.
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

    /**
     * [findReceivedPending] 의 **건별 판정판** — 주어진 id 중 이 사용자가 응답 대기 중인 것의 id 만.
     *
     * `opponent_id = :opponentId AND id IN :ids AND status = 'PENDING' AND deadline > :now`.
     * 알림 목록이 `referencePending` 을 채울 때 페이지당 **1회** 부른다.
     *
     * ## 🔴 WHERE 절 3개가 [findReceivedPending] 과 **같아야 한다 — 그게 요구사항이다**
     *
     * 이건 같은 도메인 규칙(*"이 사용자가 아직 응답을 기다리는 신청"*)의 **두 번째 구현**이다.
     * 홈의 '받은 도전장'과 알림의 *"아직 응답 대기인가"* 는 **같은 질문**이라, 조건이 갈라지는 순간
     * 두 화면이 서로 다른 답을 낸다. 한쪽 WHERE 를 고칠 일이 생기면 다른 쪽도 같이 고쳐라.
     *
     * 어긋나면 나는 증상: *"알림은 탭되는데 홈 '받은 도전장'엔 그 카드가 없다"* 또는 그 반대.
     * 🔴 **조용히 틀린다** — 두 쿼리 다 성공하고 응답도 200 이라 예외도 로그도 남지 않는다.
     *
     * **하나로 합치지 않은 이유**: 저쪽은 native 고 이쪽은 JPQL 이라 컬럼 표기부터 다르고
     * (`c.opponent_id` vs `c.opponentId`), SELECT 절·JOIN·정렬도 다르다 (저쪽은 users JOIN 한
     * 목록, 이쪽은 id 만). 조건이 겹칠 뿐 쿼리는 같은 것이 아니다. 대신
     * **`ChallengeReceivedPredicateBindingTest` 가 두 쿼리 문자열을 묶어 놓는다** — 한쪽에서
     * 조건이 빠지면 그 테스트가 깨진다.
     *
     * ## 🔴 `deadline > :now` 가 빠지면 조용히 틀린다
     *
     * 마감이 지나도 DB 의 status 는 `PENDING` 그대로다 (lazy expiry — [findReceivedPending]).
     * 즉 **`status = 'PENDING'` 만으로는 만료된 신청까지 «대기 중» 으로 집는다.** 쿼리는 성공하고
     * 행도 나오므로 로그에 아무것도 안 남는다. 경계가 `>=` 가 아니라 `>` 인 것도 저쪽과 같다
     * (마감 정각은 아직 유효 — `KstDeadlineCalculator.isExpired`).
     *
     * `status` 를 리터럴로 박은 것은 [findRecentlyCompletedByUser] 와 같은 판단이다 — 이 조회는
     * `PENDING` 만 의미가 있고, 파라미터로 열어 두면 다른 값이 흘러들어 **조용히 빈 결과**가 된다.
     *
     * JPQL 인 이유: users JOIN 이 필요 없다. 여기서 필요한 것은 **id 뿐**이라 엔티티를 물어 올
     * 이유도 없다 (`SELECT c.id`).
     *
     * IN 절 빈 컬렉션 가드는 [findActiveByUser]·[findDueByStatus] 와 같은 이유로 호출부에 있다.
     */
    @Query(
        """
        SELECT c.id
          FROM ChallengeEntity c
         WHERE c.opponentId = :opponentId
           AND c.id IN (:ids)
           AND c.status = 'PENDING'
           AND c.deadline > :now
        """,
    )
    fun findPendingReceivedIdsIn(
        @Param("opponentId") opponentId: Long,
        @Param("ids") ids: Collection<Long>,
        @Param("now") now: LocalDateTime,
    ): List<Long>

    /**
     * 탈퇴자가 **보낸** `PENDING` 챌린지를 물리 삭제 (mypage T-B4 — 회원탈퇴).
     *
     * `opponent_id = :userId` 인 것은 **대상이 아니다** — 수락할 사람이 사라져 자연 만료되므로
     * 지울 이유가 없고, 지우면 상대가 보낸 도전장을 잃는다. 전체 근거는
     * `ChallengeRepository.deletePendingSentBy` KDoc.
     *
     * ## 🔴 `status` 를 파라미터로 열지 않은 이유가 여기서는 특히 무겁다
     *
     * [findRecentlyCompletedByUser] 는 잘못된 값이 들어오면 **조용히 빈 결과**가 되는 정도지만,
     * 이건 DELETE 다 — 파라미터로 받으면 `IN_PROGRESS`/`COMPLETED` 가 흘러들어 **보존해야 할
     * 챌린지를 지우는 쿼리**가 된다. 계약서·서명·사진·결과가 딸린, 상대의 기록이기도 한
     * row 들이다. 넣을 수 없게 리터럴로 박는다.
     *
     * ⚠️ 벌크 DELETE 라 영속 컨텍스트를 거치지 않는다 (`FriendshipJpaRepository.deleteAllByUserId`
     * 와 같은 주의). 탈퇴 경로는 삭제 후 challenges 를 다시 읽지 않는다.
     */
    @Modifying
    @Query(
        """
        DELETE FROM ChallengeEntity c
         WHERE c.challengerId = :userId
           AND c.status = 'PENDING'
        """,
    )
    fun deletePendingSentBy(@Param("userId") userId: Long): Int
}
