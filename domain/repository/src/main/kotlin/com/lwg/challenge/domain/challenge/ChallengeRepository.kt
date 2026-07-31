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
