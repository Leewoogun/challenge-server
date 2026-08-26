package com.lwg.challenge.service.challenge

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeResult
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.ChallengeVerdict
import com.lwg.challenge.domain.challenge.Outcome
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.verification.VerificationRepository
import com.lwg.challenge.domain.verification.VerificationStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 홈 목록 단일 read 트랜잭션 — 진행 중 챌린지 + **최근 판정된 챌린지**.
 *
 * 쿼리 4개로 고정 (N+1 회피):
 *  1. challenges 조회 — 진행 중 (challenger OR opponent + status IN + ORDER BY deadline ASC)
 *  2. challenges 조회 — 최근 판정 (status = COMPLETED + completed_at > since, ORDER BY completed_at DESC)
 *  3. users 일괄 조회 (상대방 N 명 닉네임)
 *  4. verifications 일괄 조회 (challenge_id IN N)
 *
 * 🔴 **3 개에서 4 개로 늘어난 것은 1 번을 확장하지 않고 2 번을 더했기 때문이다.** 두 목록은 정렬
 * 기준이 다르다(급한 순 vs 최신 순) — 한 쿼리로 합치면 `CASE` 정렬이 되고 읽을 수 없어진다
 * (`ChallengeRepository.findRecentlyCompletedByUser` KDoc). **3·4 번은 여전히 1회씩이다** —
 * 두 목록을 합친 뒤에 조회하므로 카드가 늘어도 쿼리는 늘지 않는다. 그게 이 클래스가 지키는 값이다.
 *
 * ## 🔴 왜 판정된 챌린지가 "홈" 에 섞이는가
 *
 * 판정되는 순간 카드가 사라지는데 **푸시도 없고 지난 챌린지 화면도 없다** — 결과에 도달할 경로가
 * 0 이 된다 (계약 §2.1). 그래서 [completedRetentionDays] 동안만 목록에 남긴다. 이건 정식 해법이
 * 아니라 시간벌기이며, 정식 해법(지난 챌린지 화면 + 조회 API)은 백로그에 있다.
 *
 * api-contract.md 의 1차 정책: 진행 중은 `IN_PROGRESS` 만. CONTRACT_SIGNING 등 확장 시
 * [ACTIVE_STATUSES] 만 수정.
 */
@Service
class ActiveChallengeService(
    private val userRepository: UserRepository,
    private val challengeRepository: ChallengeRepository,
    private val verificationRepository: VerificationRepository,
    private val clock: Clock,
    /**
     * 판정된 챌린지를 홈 목록에 남겨 두는 기간(일). 계약 §2.3 의 `N`.
     *
     * 값의 의미와 7일인 이유는 `application.yml` 의 `challenge.result.completed-retention-days`
     * 주석에 있다 — 여기서 되풀이하지 않는다. 코드가 아는 것은 *"이만큼 이전은 안 보여준다"* 뿐이다.
     */
    @Value("\${challenge.result.completed-retention-days}") private val completedRetentionDays: Long,
) {

    @Transactional(readOnly = true)
    fun getActiveChallenges(userId: Long): List<ActiveChallengeView> {
        val inProgress: List<Challenge> = challengeRepository.findActiveByUser(userId, ACTIVE_STATUSES)
        // 시각은 반드시 주입된 Clock(KST) 으로 얻는다 — `completed_at` 과 같은 축이어야 한다 (ADR-0010).
        val since: LocalDateTime = LocalDateTime.now(clock).minusDays(completedRetentionDays)
        val recentlyCompleted: List<Challenge> =
            challengeRepository.findRecentlyCompletedByUser(userId, since)

        // 🔴 이어붙이는 순서가 곧 응답 순서다 — 진행 중(행동 대상)이 먼저, 결과(확인 대상)가 뒤다
        //    (계약 §2.3). 각 리스트의 내부 정렬은 저장소가 이미 보장한다.
        val challenges: List<Challenge> = inProgress + recentlyCompleted
        if (challenges.isEmpty()) return emptyList()

        val opponentIds: Set<Long> = challenges.map { c ->
            if (c.challengerId == userId) c.opponentId else c.challengerId
        }.toSet()
        val opponentsById = userRepository.findAllByIds(opponentIds)

        val challengeIds: List<Long> = challenges.mapNotNull { it.id }
        val verificationStatusByKey: Map<Pair<Long, Long>, VerificationStatus> =
            verificationRepository.findAllByChallengeIds(challengeIds)
                .associate { v -> (v.challengeId to v.userId) to v.status }

        return challenges.map { c ->
            val challengeId: Long = c.id ?: error("영속화된 challenge 의 id 는 null 일 수 없습니다")
            val isChallenger: Boolean = c.challengerId == userId
            val opponentUserId: Long = if (isChallenger) c.opponentId else c.challengerId
            // opponentMission 은 V5 이후 nullable — 상대가 수락하며 채운다.
            // 여기서 다루는 챌린지는 IN_PROGRESS 이거나 COMPLETED(= 둘 다 수락을 거친 상태)뿐이라
            // 항상 non-null 이지만, 수동 시드 등으로 비어 있는 row 가 섞여도 응답이 깨지지 않도록
            // 빈 문자열로 대체한다.
            val acceptedOpponentMission: String = c.opponentMission.orEmpty()
            val myMission: String = if (isChallenger) c.challengerMission else acceptedOpponentMission
            val opponentMission: String = if (isChallenger) acceptedOpponentMission else c.challengerMission
            val opponentNickname: String = opponentsById[opponentUserId]?.nickname ?: UNKNOWN_OPPONENT
            val myStatus: VerificationStatus =
                verificationStatusByKey[challengeId to userId] ?: VerificationStatus.PENDING
            val opponentStatus: VerificationStatus =
                verificationStatusByKey[challengeId to opponentUserId] ?: VerificationStatus.PENDING

            ActiveChallengeView(
                challengeId = challengeId,
                status = c.status,
                result = c.result,
                // 🔴 뒤집기 규칙을 여기서 새로 쓰지 않는다. `ChallengeVerdict.outcomeFor` 호출이
                //    전부다 — 판정·환산 규칙은 그 object 한 곳에만 산다 (ChallengeVerdict KDoc).
                //    특히 BOTH_LOSE 는 뒤집지 않고 Outcome.BOTH_LOSE 로 그대로 나가는데,
                //    직접 쓰면 LOSE 로 접어 버리기 쉬운 값이다 (Outcome KDoc).
                myResult = c.result?.let { ChallengeVerdict.outcomeFor(it, isChallenger) },
                myMission = myMission,
                opponentNickname = opponentNickname,
                opponentMission = opponentMission,
                deadline = c.deadline,
                myVerificationStatus = myStatus,
                opponentVerificationStatus = opponentStatus,
                bet = c.betContent,
            )
        }
    }

    companion object {
        private val ACTIVE_STATUSES: Set<ChallengeStatus> = setOf(ChallengeStatus.IN_PROGRESS)
        private const val UNKNOWN_OPPONENT = "(알 수 없음)"
    }
}

/**
 * 현재 사용자 시점에서 정리된 홈 카드 한 건 — 진행 중이거나 **최근 판정된** 챌린지다.
 *
 * `myMission` / `opponentMission` 은 현재 사용자가 challenger 인지 opponent 인지에 따라 자동 분기.
 * verification row 가 없는 경우 양측 모두 PENDING.
 */
data class ActiveChallengeView(
    val challengeId: Long,
    /** `IN_PROGRESS` 또는 `COMPLETED` 둘 중 하나만 담긴다 — 이 서비스가 그 둘만 조회한다. */
    val status: ChallengeStatus,
    /** 역할 기준 결과 원본. `IN_PROGRESS` 면 null. "양쪽 다 졌다" 를 구분해야 하는 화면이 본다. */
    val result: ChallengeResult?,
    /**
     * 내 시점 결과. `IN_PROGRESS` 면 null.
     *
     * [result] 를 [ChallengeVerdict.outcomeFor] 로 환산한 값이다 — 이 카드의 나머지 필드
     * (`myMission` 등)와 축을 맞춘다. 앱이 직접 뒤집으려면 *"내가 challenger 인가"* 를 알아야
     * 하는데 이 응답에 그 정보가 없다 (계약 §2.2).
     */
    val myResult: Outcome?,
    val myMission: String,
    val opponentNickname: String,
    val opponentMission: String,
    val deadline: LocalDateTime,
    val myVerificationStatus: VerificationStatus,
    val opponentVerificationStatus: VerificationStatus,
    val bet: String,
)
