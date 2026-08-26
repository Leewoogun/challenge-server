package com.lwg.challenge.domain.challenge

import java.time.LocalDate
import java.time.LocalDateTime

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

    /**
     * 최근 판정된 챌린지 — `(challenger = :u OR opponent = :u) AND status = COMPLETED
     * AND completed_at > :since`, `completed_at DESC`.
     *
     * 홈 목록(`/challenges/active`)이 진행 중 카드 **뒤에** 이어 붙이는 결과 카드다 (계약 §2.3).
     *
     * ## 🔴 [findActiveByUser] 를 확장하지 않고 메서드를 나눈 이유
     *
     * 두 목록은 **정렬 기준이 다르다** — 진행 중은 `deadline ASC`(급한 순, 행동 대상)이고
     * 결과는 `completed_at DESC`(최신 순, 확인 대상)다. 한 쿼리로 합치면 상태에 따라 정렬 키가
     * 바뀌는 `CASE` 정렬이 되고, **그게 정확히 나중에 읽을 수 없게 되는 종류의 SQL 이다.**
     * 조회가 1회 늘지만 두 쿼리 다 인덱스를 탄다 — 목록 하나 그리는 데 쿼리 하나 더 쓰는 값으로
     * 읽히는 SQL 을 산다.
     *
     * ## 🔴 `EXPIRED` 는 여기 걸리지 않는다
     *
     * 조건이 `status = COMPLETED` 라 만료(= 계약 미체결)는 애초에 대상이 아니다. 승부가 성립한
     * 적이 없으므로 결과 카드로 보여줄 것도 없다 (계약 §2.3 / §3.1).
     *
     * @param since 이 시각 **이후**에 판정된 것만. 호출부가 `now - N일` 로 계산해 넘긴다 —
     *   "며칠" 을 아는 것은 프로퍼티를 주입받는 서비스이지 저장소가 아니다.
     */
    fun findRecentlyCompletedByUser(userId: Long, since: LocalDateTime): List<Challenge>

    /** id 로 단건 조회. 없으면 null. (수락/거절/취소의 권한·상태 검증 진입점) */
    fun findById(id: Long): Challenge?

    /**
     * 받은 도전장 목록 — 내가 `opponent` 인 `PENDING` 챌린지 중 **마감이 지나지 않은 것**.
     *
     * `created_at DESC` 정렬. 챌린저 정보(닉네임/프로필)를 users JOIN 으로 함께 가져와 N+1 을 피한다.
     *
     * 마감 지난 row 는 DB 에서 `PENDING` 인 채로 남고 **응답에서만 제외**된다 (lazy expiry —
     * `EXPIRED` 로 전이시킬 배치가 아직 없다. challenge-create spec 리스크 항목).
     *
     * @param opponentId 조회자(= 도전장을 받은 사람) id
     * @param now 마감 비교 기준 시각. **KST LocalDateTime** — `challenges.deadline` 과 같은 축이어야 한다 (ADR-0010).
     */
    fun findReceivedPending(opponentId: Long, now: LocalDateTime): List<ReceivedChallenge>

    /**
     * 두 사용자 사이의 **같은 날짜** 중복 챌린지를 양방향으로 조회한다.
     *
     * `((challenger=a AND opponent=b) OR (challenger=b AND opponent=a)) AND challenge_date=? AND status IN ?`
     *
     * 1:1 대결 컨셉상 하루 한 판이므로, 내가 걸었든 상대가 걸었든 모두 중복이다
     * (api-contract 오픈이슈 3 — 2026-07-28 양방향 확정).
     *
     * service 가 반환된 row 의 방향/상태를 보고 사용자에게 보여줄 메시지를 분기하므로,
     * boolean 이 아니라 목록을 돌려준다.
     */
    fun findConflicting(
        userA: Long,
        userB: Long,
        challengeDate: LocalDate,
        statuses: Collection<ChallengeStatus>,
    ): List<Challenge>

    /**
     * `status IN (:statuses) AND deadline <= :now` — **배치 판정 대상 선정** (challenge-result T-B1).
     *
     * `deadline` 오름차순. 사용자 축이 전혀 없는 유일한 조회다 — 배치는 전 사용자의 챌린지를 본다.
     *
     * ## 🔴 기준이 **실행 시각이 아니라 `deadline`** 이라는 게 소급 판정의 전부다
     *
     * "지난 자정에 마감된 것" 을 찾는 게 아니라 "마감이 이미 지난 것" 을 전부 찾는다. 그래서
     * 서버가 자정에 죽어 있었든 사흘을 죽어 있었든, **다음 실행 한 번이 밀린 분을 전부 집는다.**
     * 스케줄러는 "언제 볼까" 만 정하고 소급성은 이 쿼리가 보장한다 — 재시도 큐도, 마지막 실행
     * 시각을 기록하는 테이블도 필요 없는 이유다.
     *
     * ## 🔴 경계는 `<=` 다 — 마감 정각을 **포함**한다
     *
     * [findReceivedPending] 의 `deadline > :now` 와 정확히 상보적이다. 정각이 되는 순간 그쪽
     * 목록에서 사라지고 이쪽 판정 대상이 된다 — 두 조건 사이에 **어느 쪽에도 속하지 않는 틈이
     * 없다.** (accept 경로의 `KstDeadlineCalculator.isExpired` 는 `<` 로 사용자에게 유리한 쪽을
     * 택하는데, 배치는 00:05 에 도므로 정각 동시성이 실제로 발생하지 않는다.)
     *
     * `idx_challenges_deadline_status` 가 이 조회를 받는다 (V1__init.sql).
     *
     * @param statuses 대상 상태. `IN_PROGRESS` 면 판정 대상, `PENDING` 이면 만료 대상이다 —
     *   둘은 처리가 완전히 달라서(전적 집계 유무) 호출부가 따로 부른다.
     * @param now 마감 비교 기준 시각. **KST LocalDateTime** — `challenges.deadline` 과 같은 축이어야 한다 (ADR-0010).
     */
    fun findDueByStatus(statuses: Collection<ChallengeStatus>, now: LocalDateTime): List<Challenge>

    /** 저장 (id == null 이면 INSERT, 있으면 MERGE). 상태 전이(수락/거절)에 사용. */
    fun save(challenge: Challenge): Challenge

    /**
     * 저장 후 즉시 flush.
     *
     * 신규 생성(INSERT) 분기에서 사용. `save` 만 호출하면 flush 가 트랜잭션 commit 시점으로 미뤄져
     * 제약 위반이 service 의 try/catch 밖에서 터진다 (friends 2차에서 실제로 겪은 문제 —
     * 의도한 code 700 대신 500 으로 노출됐다).
     */
    fun saveAndFlush(challenge: Challenge): Challenge

    /** row 물리 삭제 — 챌린저의 PENDING 취소에서만 사용. `CANCELED` 상태를 도입하지 않기 위한 선택. */
    fun deleteById(id: Long)
}
