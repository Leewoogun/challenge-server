package com.lwg.challenge.service.challenge

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.verification.VerificationRepository
import com.lwg.challenge.domain.verification.VerificationStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 진행 중 챌린지 목록 단일 read 트랜잭션.
 *
 * 쿼리 3개로 고정 (N+1 회피):
 *  1. challenges 조회 (challenger OR opponent + status IN + ORDER BY deadline ASC)
 *  2. users 일괄 조회 (상대방 N 명 닉네임)
 *  3. verifications 일괄 조회 (challenge_id IN N)
 *
 * api-contract.md 의 1차 정책: `IN_PROGRESS` 만 응답. CONTRACT_SIGNING 등 확장 시 [ACTIVE_STATUSES] 만 수정.
 */
@Service
class ActiveChallengeService(
    private val userRepository: UserRepository,
    private val challengeRepository: ChallengeRepository,
    private val verificationRepository: VerificationRepository,
) {

    @Transactional(readOnly = true)
    fun getActiveChallenges(userId: Long): List<ActiveChallengeView> {
        val challenges: List<Challenge> = challengeRepository.findActiveByUser(userId, ACTIVE_STATUSES)
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
            val myMission: String = if (isChallenger) c.challengerMission else c.opponentMission
            val opponentMission: String = if (isChallenger) c.opponentMission else c.challengerMission
            val opponentNickname: String = opponentsById[opponentUserId]?.nickname ?: UNKNOWN_OPPONENT
            val myStatus: VerificationStatus =
                verificationStatusByKey[challengeId to userId] ?: VerificationStatus.PENDING
            val opponentStatus: VerificationStatus =
                verificationStatusByKey[challengeId to opponentUserId] ?: VerificationStatus.PENDING

            ActiveChallengeView(
                challengeId = challengeId,
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
 * 현재 사용자 시점에서 정리된 진행 중 챌린지 한 건.
 *
 * `myMission` / `opponentMission` 은 현재 사용자가 challenger 인지 opponent 인지에 따라 자동 분기.
 * verification row 가 없는 경우 양측 모두 PENDING.
 */
data class ActiveChallengeView(
    val challengeId: Long,
    val myMission: String,
    val opponentNickname: String,
    val opponentMission: String,
    val deadline: LocalDateTime,
    val myVerificationStatus: VerificationStatus,
    val opponentVerificationStatus: VerificationStatus,
    val bet: String,
)
