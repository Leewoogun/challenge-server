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

    /**
     * 계약서 보관함 — `(challenger = :u OR opponent = :u) AND status = COMPLETED` 중
     * **`challengeDate` 가 `[monthStart, nextMonthStart)` 에 드는 것**을
     * `challenge_date DESC, id DESC` 로 반환 (mypage T-B1, 2026-08-28 월 단위 개정).
     *
     * ## 🔴 [findRecentlyCompletedByUser] 와 축이 다르다 — 그게 이 메서드가 따로 있는 이유다
     *
     * 그쪽은 `completed_at` 이고 이쪽은 **`challenge_date`** 다. 판정이 자정 직후 배치라
     * 7월 31일 챌린지의 `completed_at` 은 8월 1일 00:05 다 — `completed_at` 으로 범위를 자르면
     * **7월 마지막 날 챌린지가 8월 응답에 들어가서**, 카드에 적힌 날짜(7/31)와 화면 상단의 월
     * 표시(8월)가 한 화면에서 서로 다른 말을 한다. 반대로 홈 목록은 *"판정이 방금 났다"* 를
     * 보여주는 것이 목적이라 `completed_at` 이 맞다. **같은 row 라도 화면의 목적이 다르면
     * 조회 축이 다르다.**
     *
     * ## 🔴 `EXPIRED` 는 대상이 아니다
     *
     * 만료는 **성립조차 못 한 신청**이라 계약서·서명·사진·결과가 전부 없다 — 보관함에 올리면
     * 증거가 0인 카드가 된다. 조건이 `status = COMPLETED` 라 애초에 걸리지 않는다.
     *
     * ## 페이지네이션 — **필요가 사라졌다** (2026-08-28 월 단위 개정)
     *
     * 1차(전체 반환)에서는 이 목록이 *"프로젝트 최초의 무한히 자라는 목록"* 이라 페이지네이션
     * 도입 1순위였다. 월 단위가 되면서 **한 응답의 상한이 한 달치로 고정**됐다 — 같은 상대와는
     * 하루 한 판이 상한이고([findConflicting] 이 `challenge_date` 로 막는다), 한 달에 담기는
     * 카드 수는 사실상 유계다. 다른 엔드포인트의 페이지네이션 논의는 그대로 남지만
     * **보관함은 그 목록에서 빠진다.**
     *
     * @param monthStart 조회 월의 1일 (포함)
     * @param nextMonthStart 다음 달 1일 (**미포함**). 반개구간인 이유는 구현체 KDoc 참조
     */
    fun findCompletedHistoryByUserInMonth(
        userId: Long,
        monthStart: LocalDate,
        nextMonthStart: LocalDate,
    ): List<Challenge>

    /**
     * 이 사용자의 가장 오래된 `COMPLETED` 챌린지의 `challengeDate`. 종료 기록이 없으면 null.
     *
     * ## 🔴 보관함의 **과거 하한**이다 — 앱의 `<` 비활성 판정에 쓴다
     *
     * 하한의 목적은 *"확정적으로 빈 달의 사막으로 걸어 들어가지 않게 한다"* 이다. 이 날짜의
     * 월보다 이전은 **0건인 것이 보장**된다. 하한을 두지 않으면 화살표가 영원히 살아 있어
     * 사용자가 *"더 뒤에 있나"* 를 확인할 방법이 없고, 보관함은 *"이게 네 기록 전부다"* 라고
     * 주장하는 화면이라 **화면이 그 주장을 못 하게 된다.**
     *
     * ## 🔴 이 값은 시간이 지나도 **과거로 밀리지 않는다**
     *
     * `challengeDate` 는 생성 시점의 오늘 또는 내일이고(`KstDeadlineCalculator`), `COMPLETED` 는
     * 마감이 지나야 되는 상태다. 그래서 새로 쌓이는 기록은 항상 기존 최소값보다 뒤다 —
     * **하한이 한 번 정해지면 내려가지 않는다.** 죽었던 화살표가 나중에 되살아나는 일이 없다.
     *
     * 0건이면 null 이고, 그때 호출부는 하한을 **이번 달**로 삼는다(= 상한과 같아져 양쪽 화살표가
     * 모두 죽는다). 아무 데도 기록이 없는데 뒤로 걷게 두면 *"더 찾아보면 있을지도"* 라는 거짓
     * 기대를 만든다.
     */
    fun findEarliestCompletedChallengeDate(userId: Long): LocalDate?

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

    /**
     * [userId] 가 **challenger 인 `PENDING`** 챌린지를 전부 물리 삭제. **회원탈퇴 전용** (mypage T-B4).
     *
     * ## 🔴 왜 보낸 것만 지우고 받은 것은 두는가 — 정책의 절반이 반대 방향이다
     *
     * **보낸 것(challenger)**: 남겨 두면 상대가 그 도전장을 **수락할 수 있다.** 그런데 탈퇴자는
     * 로그인이 불가해 인증을 올릴 수 없으므로, 수락되는 순간 **결과가 이미 정해진 챌린지**가
     * 새로 시작된다. 그건 승부가 아니다.
     *
     * **받은 것(opponent)**: 수락 권한이 탈퇴자에게 있는데 그가 없으므로 **아무도 수락할 수
     * 없다.** 마감이 지나면 자연 만료되므로 지울 이유가 없다 — 지우면 상대(살아 있는 사용자)가
     * *"내가 보낸 도전장"* 을 잃는다.
     *
     * ## 왜 물리 삭제가 안전한가
     *
     * `PENDING` 에는 계약서·서명·사진·결과가 **하나도 없다.** 보존할 증거가 0 이라 [deleteById]
     * 로 챌린저가 직접 취소하는 것과 **결과가 정확히 같다** — 이미 존재하는 그 경로를 탈퇴가
     * 일괄로 밟는 것뿐이다.
     *
     * ⚠️ `IN_PROGRESS`·`COMPLETED` 는 **절대 대상이 아니다.** 조건이 `status = 'PENDING'`
     * 리터럴로 고정돼 있어 다른 상태가 흘러들 수 없다 (`ChallengeJpaRepository` 의 같은 판단).
     *
     * @return 삭제된 row 수. 호출부는 로그에만 쓴다 — 0 건도 정상이다.
     */
    fun deletePendingSentBy(userId: Long): Int
}
