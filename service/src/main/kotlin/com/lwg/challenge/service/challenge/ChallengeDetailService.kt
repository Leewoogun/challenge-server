package com.lwg.challenge.service.challenge

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeResult
import com.lwg.challenge.domain.challenge.ChallengeVerdict
import com.lwg.challenge.domain.challenge.Outcome
import com.lwg.challenge.domain.challenge.RelativeParty
import com.lwg.challenge.domain.common.exception.OneButtonDialogException
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.contract.Contract
import com.lwg.challenge.domain.contract.ContractRepository
import com.lwg.challenge.domain.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 챌린지 상세 조회 (soul-oath T-B3). `GET /api/v1/challenges/{id}`.
 *
 * ## 요청자 기준("나/상대")으로 뒤집어 준다
 *
 * 화면 문구가 전부 "나의 미션", "상대 서명" 식이라, 역할 기준으로 주면 앱이 `/users/me` 를
 * 한 번 더 불러 자기가 challenger 인지 비교하고 미션·서명·결과·기권을 전부 뒤집어야 했다.
 * 서버는 JWT 로 요청자를 이미 알므로 여기서 뒤집는다 — 홈 카드(`/challenges/active`)·
 * 받은 도전장과 같은 방향이다.
 *
 * 계약서가 아직 없거나 미서명이면 해당 필드는 null 이다.
 */
@Service
class ChallengeDetailService(
    private val challengeRepository: ChallengeRepository,
    private val contractRepository: ContractRepository,
    private val userRepository: UserRepository,
) {

    @Transactional(readOnly = true)
    fun getDetail(me: Long, challengeId: Long): ChallengeDetailView {
        val challenge: Challenge = challengeRepository.findById(challengeId)
            ?: throw OneButtonDialogException(MSG_NOT_FOUND)

        // 당사자만 볼 수 있다 — 계약서에는 양측의 미션·서명이 들어 있다.
        if (me != challenge.challengerId && me != challenge.opponentId) {
            throw SnackbarException(MSG_NOT_MINE)
        }

        val users = userRepository.findAllByIds(setOf(challenge.challengerId, challenge.opponentId))
        val contract: Contract? = contractRepository.findByChallengeId(challengeId)

        val isChallenger = me == challenge.challengerId
        val challengerParty = PartyView(
            userId = challenge.challengerId,
            nickname = users[challenge.challengerId]?.nickname ?: UNKNOWN,
            profileImageUrl = users[challenge.challengerId]?.profileImageUrl,
            mission = challenge.challengerMission,
        )
        val opponentParty = PartyView(
            userId = challenge.opponentId,
            nickname = users[challenge.opponentId]?.nickname ?: UNKNOWN,
            profileImageUrl = users[challenge.opponentId]?.profileImageUrl,
            // 상대가 아직 수락 전이면 미션이 없다 (V5 이후 nullable).
            mission = challenge.opponentMission,
        )

        return ChallengeDetailView(
            challengeId = challengeId,
            status = challenge.status.name,
            // 🔴 뒤집기 규칙을 여기서 새로 쓰지 않는다 — `ChallengeVerdict.outcomeFor` 만 부른다.
            //    COMPLETED 가 아니면 result 가 null 이라는 불변식은 판정 배치가 세운다(Challenge KDoc).
            myResult = challenge.result?.let { ChallengeVerdict.outcomeFor(it, isChallenger) },
            forfeitedBy = challenge.forfeitedBy?.let {
                if (it == me) RelativeParty.ME else RelativeParty.OPPONENT
            },
            challengeDate = challenge.challengeDate.toString(),
            deadline = challenge.deadline,
            betContent = challenge.betContent,
            me = if (isChallenger) challengerParty else opponentParty,
            opponent = if (isChallenger) opponentParty else challengerParty,
            contract = contract?.let {
                ContractView(
                    content = it.content,
                    isFinalized = it.isFinalized,
                    mySignature = if (isChallenger) it.challengerSignatureData else it.opponentSignatureData,
                    mySignedAt = if (isChallenger) it.challengerSignedAt else it.opponentSignedAt,
                    opponentSignature = if (isChallenger) it.opponentSignatureData else it.challengerSignatureData,
                    opponentSignedAt = if (isChallenger) it.opponentSignedAt else it.challengerSignedAt,
                )
            },
        )
    }

    companion object {
        private const val UNKNOWN = "(알 수 없음)"
        const val MSG_NOT_FOUND = "챌린지를 찾을 수 없어요"
        const val MSG_NOT_MINE = "내 챌린지가 아니에요"
    }
}

/** 요청자 기준 챌린지 상세. [me] 는 요청자, [opponent] 는 상대다 (역할과 무관). */
data class ChallengeDetailView(
    val challengeId: Long,
    val status: String,
    /**
     * 내 시점 결과. `status == COMPLETED` 가 아니면 항상 null (challenge-result 계약 §1).
     * 역할 기준 [ChallengeResult] 를 [ChallengeVerdict.outcomeFor] 로 환산한 값이다.
     */
    val myResult: Outcome?,
    /** 기권한 쪽. **null = 기권 없이 끝났거나 아직 안 끝났다** (challenge-forfeit). */
    val forfeitedBy: RelativeParty?,
    val challengeDate: String,
    val deadline: LocalDateTime,
    val betContent: String,
    val me: PartyView,
    val opponent: PartyView,
    val contract: ContractView?,
)

data class PartyView(
    val userId: Long,
    val nickname: String,
    val profileImageUrl: String?,
    val mission: String?,
)

data class ContractView(
    val content: String,
    val isFinalized: Boolean,
    /** 벡터 스트로크 JSON 문자열. null = 미서명. */
    val mySignature: String?,
    val mySignedAt: LocalDateTime?,
    val opponentSignature: String?,
    val opponentSignedAt: LocalDateTime?,
)
