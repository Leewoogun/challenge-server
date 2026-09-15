package com.lwg.challenge.service.challenge

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeResult
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
 * ## 왜 "나/상대" 로 뒤집지 않는가
 *
 * 상세는 **계약서를 보여주는 화면**이라 양쪽을 다 그린다. `myMission`/`opponentMission` 으로
 * 뒤집어 주면 **"이 서명이 누구 것인지" 를 클라가 다시 계산해야 한다.**
 * 홈 카드(`/challenges/active`)는 "내 시점" 이 맞지만 계약서는 아니다 —
 * **같은 데이터라도 화면의 성격이 다르면 시점이 다르다** (api-contract §3).
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

        return ChallengeDetailView(
            challengeId = challengeId,
            status = challenge.status.name,
            // 도메인 값을 그대로 흘린다. COMPLETED 가 아니면 null 이라는 불변식은 판정 배치가
            // 세우는 것이고(Challenge KDoc), 여기서 다시 검사하면 규칙이 두 곳에 살게 된다.
            result = challenge.result,
            forfeitedBy = challenge.forfeitedBy,
            challengeDate = challenge.challengeDate.toString(),
            deadline = challenge.deadline,
            betContent = challenge.betContent,
            challenger = PartyView(
                userId = challenge.challengerId,
                nickname = users[challenge.challengerId]?.nickname ?: UNKNOWN,
                profileImageUrl = users[challenge.challengerId]?.profileImageUrl,
                mission = challenge.challengerMission,
            ),
            opponent = PartyView(
                userId = challenge.opponentId,
                nickname = users[challenge.opponentId]?.nickname ?: UNKNOWN,
                profileImageUrl = users[challenge.opponentId]?.profileImageUrl,
                // 상대가 아직 수락 전이면 미션이 없다 (V5 이후 nullable).
                mission = challenge.opponentMission,
            ),
            contract = contract?.let {
                ContractView(
                    content = it.content,
                    isFinalized = it.isFinalized,
                    challengerSignature = it.challengerSignatureData,
                    challengerSignedAt = it.challengerSignedAt,
                    opponentSignature = it.opponentSignatureData,
                    opponentSignedAt = it.opponentSignedAt,
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

data class ChallengeDetailView(
    val challengeId: Long,
    val status: String,
    /**
     * 판정 결과. `status == COMPLETED` 가 아니면 항상 null (challenge-result 계약 §1).
     *
     * 🔴 **역할 기준**이다 — 뒤집지 않는다. 이 화면이 `challenger`/`opponent` 를 역할 그대로
     * 주고 있어서(위 KDoc), result 만 "내 시점" 으로 바꾸면 **같은 응답 안에 두 기준이 섞인다.**
     */
    val result: ChallengeResult?,
    /**
     * 기권한 사람의 userId. **null = 기권 없이 끝났거나 아직 안 끝났다** (challenge-forfeit).
     *
     * 🔴 `Boolean` 이 아닌 이유: 불리언이면 앱이 *"누가 기권했나"* 를
     * `forfeited && result == OPPONENT_WIN → challenger 가 기권` 으로 **역산**해야 하고,
     * 그건 판정 규칙의 사본을 앱에 심는 것이다. userId 면 [PartyView.userId] 와 **비교만** 하면 된다.
     */
    val forfeitedBy: Long?,
    val challengeDate: String,
    val deadline: LocalDateTime,
    val betContent: String,
    val challenger: PartyView,
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
    val challengerSignature: String?,
    val challengerSignedAt: LocalDateTime?,
    val opponentSignature: String?,
    val opponentSignedAt: LocalDateTime?,
)
