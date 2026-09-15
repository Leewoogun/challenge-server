package com.lwg.challenge.service.challenge

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.ChallengeVerdict
import com.lwg.challenge.domain.challenge.Forfeit
import com.lwg.challenge.domain.challenge.Outcome
import com.lwg.challenge.domain.friendrecord.FriendRecord
import com.lwg.challenge.domain.friendrecord.FriendRecordRepository
import com.lwg.challenge.domain.userrecord.UserRecord
import com.lwg.challenge.domain.userrecord.UserRecordRepository
import com.lwg.challenge.domain.userrecord.applying
import com.lwg.challenge.domain.verification.VerificationRepository
import com.lwg.challenge.domain.verification.VerificationStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 챌린지 **1건** 판정 — 트랜잭션 경계가 여기다 (challenge-result T-B2·T-B3, 계약 §3.1·§3.3·§3.4).
 *
 * ## 🔴 왜 [ChallengeJudgementRunner] 와 빈을 나눴나 — self-invocation
 *
 * 대상 선정 루프와 건별 판정을 **같은 빈**에 두면, 루프가 `this.judge(id)` 를 부르는 순간
 * Spring AOP 프록시를 타지 않아 **`@Transactional` 이 아무 효과가 없다.** 어노테이션은 그대로
 * 붙어 있고, 컴파일도 되고, 테스트도 초록이다 — 운영에서만 한 챌린지의 상태 변경과 전적 갱신이
 * 서로 다른 (사실상 없는) 트랜잭션에서 일어나 부분 커밋이 남는다.
 *
 * 자기 호출을 프록시로 되돌리는 방법(`AopContext.currentProxy()`, 자기 자신 지연 주입)도 있지만
 * 전부 "잊으면 조용히 깨지는" 쪽이다. **빈을 나누는 것이 유일하게 확실한 방법**이라 나눴다.
 * 이 클래스에 대상 선정 로직을 다시 끌어오지 마라.
 *
 * ## 🔴 멱등 — 진입 시 상태를 **다시 확인**한다
 *
 * Runner 가 목록을 뽑은 시점과 이 메서드가 도는 시점 사이에 상태가 바뀔 수 있다 (재기동 직후
 * cron 과 startup 실행이 겹치는 경우, 앞 건이 오래 걸린 경우). 그때 두 번 판정하면
 * **전적이 두 번 세어진다** — `total_challenges` 는 되돌릴 방법이 없다. 그래서 `IN_PROGRESS`
 * 가 아니면 아무것도 하지 않고 `false` 를 돌려준다.
 *
 * ## 🔴 알림을 보내지 않는다 — 이건 구조로 보장된다
 *
 * 계약 §3.2 가 *"발송하지 않는다. notifications row 증가 0"* 이라고 못박았다.
 * **생성자에 `ApplicationEventPublisher` 가 없는 것 자체가 그 보장이다** — 넣는 순간 이 문단이
 * 거짓이 되므로, 결과 알림이 범위에 들어오는 날 이 KDoc 부터 고쳐라.
 *
 * ⚠️ **기권(challenge-forfeit)은 이 문단을 깨지 않았다.** `CHALLENGE_FORFEITED` 를 발행하는
 * 것은 [ChallengeForfeitService] 이고 이 빈은 여전히 publisher 를 모른다. 그래서 **자정 배치가
 * 판정한 건은 기권 건이든 아니든 알림 0건**이라는 사실이 그대로다.
 *
 * ## 🔴 기권도 **이 함수를 탄다** (challenge-forfeit T-B3)
 *
 * 기권은 즉시 확정이지만 판정·`COMPLETED` 전이·전적 집계를 **새로 쓰지 않는다.**
 * [ChallengeForfeitService] 가 `challenges.forfeited_by` 만 기록하고 이 메서드를 그대로 부른다.
 * 그래서 기권 경로와 자정 배치 경로가 **같은 코드**이며, 갈릴 수가 없다.
 *
 * 🔴 **기권 전용 집계 함수를 만들지 마라.** 만드는 순간 `ChallengeVerdict` KDoc 이 경계하는
 * *"두 벌의 규칙"* 이 실제로 생긴다.
 */
@Service
class ChallengeJudgementService(
    private val challengeRepository: ChallengeRepository,
    private val verificationRepository: VerificationRepository,
    private val userRecordRepository: UserRecordRepository,
    private val friendRecordRepository: FriendRecordRepository,
    private val clock: Clock,
) {

    /**
     * `IN_PROGRESS` 챌린지 1건을 판정하고 전적까지 **한 트랜잭션에서** 반영한다.
     *
     * 상태 전이·verification FAILED 전이·`user_stats`·`friend_records` 가 전부 같은 트랜잭션이라
     * 하나라도 터지면 전부 되돌아간다 (계약 §3.3). 부분 반영된 전적은 소급 수정이 불가능하므로
     * 이 원자성이 이 메서드의 존재 이유에 가깝다.
     *
     * @return 실제로 판정했으면 true. 대상이 아니어서(없음/이미 처리됨) 건너뛰었으면 false.
     *   예외는 삼키지 않는다 — 실패는 Runner 가 세고 로그로 남긴다.
     */
    @Transactional
    fun judge(challengeId: Long): Boolean {
        val challenge = challengeRepository.findById(challengeId) ?: return false
        // 멱등 가드. 목록을 뽑은 뒤 상태가 바뀌었으면 여기서 끝난다.
        if (challenge.status != ChallengeStatus.IN_PROGRESS) return false

        val now = LocalDateTime.now(clock)
        val verifications = verificationRepository.findAllByChallengeIds(listOf(challengeId))
            .associateBy { it.userId }

        // 🔴 row 부재 = 미인증. PENDING / FAILED 도 전부 미인증이다 (ChallengeVerdict KDoc).
        val challengerVerified = verifications[challenge.challengerId]?.status == VerificationStatus.VERIFIED
        val opponentVerified = verifications[challenge.opponentId]?.status == VerificationStatus.VERIFIED

        // 🔴 userId → 역할 환산은 **호출부 책임**이다 (ChallengeVerdict KDoc: "환산 책임을
        //    호출부에 두고 이 함수는 이미 환산된 값만 본다"). 기권 여부를 판정에 반영하는 곳도
        //    여기 한 곳뿐이라, 기권 경로와 배치 경로가 같은 줄을 탄다.
        val result = ChallengeVerdict.decide(challengerVerified, opponentVerified, challenge.forfeitOf())

        // 계약 §3.1 "미인증 시 자동 패배" 이행. 🔴 row 가 없으면 **만들지 않는다** —
        // VerificationService 가 "수락 없이 인증된 챌린지를 만들지 않는다" 며 거부하는 것과 같은 이유로,
        // 배치가 없던 인증 row 를 창조하면 데이터가 깨진 사실이 감춰진다.
        // VERIFIED 인 쪽은 건드리지 않는다.
        listOf(challenge.challengerId, challenge.opponentId).forEach { userId ->
            val verification = verifications[userId] ?: return@forEach
            if (verification.status == VerificationStatus.PENDING) {
                verificationRepository.save(verification.copy(status = VerificationStatus.FAILED))
            }
        }

        challengeRepository.save(
            challenge.copy(
                status = ChallengeStatus.COMPLETED,
                result = result,
                completedAt = now,
            ),
        )

        val challengerOutcome = ChallengeVerdict.outcomeFor(result, isChallenger = true)
        val opponentOutcome = ChallengeVerdict.outcomeFor(result, isChallenger = false)

        // user_stats — 신규 사용자는 row 가 없을 수 있어 0 채움에서 시작한다 (계약 §3.3 "row 부재").
        userRecordRepository.save(recordOf(challenge.challengerId).applying(challengerOutcome, now))
        userRecordRepository.save(recordOf(challenge.opponentId).applying(opponentOutcome, now))

        // friend_records — 방향 있는 2행. 각 행에는 **그 행 주인의** outcome 을 넘긴다 (계약 §3.4).
        saveFriendRecord(challenge.challengerId, challenge.opponentId, challengerOutcome)
        saveFriendRecord(challenge.opponentId, challenge.challengerId, opponentOutcome)

        return true
    }

    /**
     * `PENDING` 인 채 마감을 넘긴 챌린지를 `EXPIRED` 로 정리한다 (계약 §3.1 후단).
     *
     * ## 🔴 이건 판정이 **아니다**
     *
     * `result` 는 null 로 남고 `completedAt` 도 건드리지 않으며, **전적도 `friend_records` 도
     * 갱신하지 않는다.** 상대가 수락한 적이 없어 계약서가 체결되지 않았고, 승부가 성립한 적이
     * 없기 때문이다. 여기서 `BOTH_LOSE` 로 집계해 버리면 *"도전장을 받고 무시당한 사람"* 의
     * 패배 수가 올라간다 — 본인은 아무것도 하지 않았는데.
     *
     * `status == COMPLETED ⟺ result != null` 이라는 계약 §1 의 불변식도 이 선택이 지킨다.
     *
     * 이 전이가 하는 일은 응답에서만 걸러지던 lazy expiry 를 **DB 상태까지 정리**하는 것뿐이다
     * (`findReceivedPending` 의 `deadline > :now` 필터가 이미 목록에서는 빼고 있었다).
     *
     * @return 실제로 만료시켰으면 true. `PENDING` 이 아니면(이미 EXPIRED / 수락됨) false.
     */
    @Transactional
    fun expire(challengeId: Long): Boolean {
        val challenge = challengeRepository.findById(challengeId) ?: return false
        if (challenge.status != ChallengeStatus.PENDING) return false

        challengeRepository.save(challenge.copy(status = ChallengeStatus.EXPIRED))
        return true
    }

    /**
     * `forfeited_by`(userId) 를 [Forfeit] 역할로 환산한다. 기권이 없으면 null.
     *
     * 🔴 **당사자가 아닌 id 가 들어 있으면 터뜨린다.** [ChallengeForfeitService] 가 당사자
     * 검증을 통과한 요청자만 이 컬럼에 쓰므로 정상 경로에서는 도달하지 않는다. 그런 row 가
     * 실제로 나타났다면 데이터가 깨진 것이고, 그때 **조용히 `null` 로 떨어뜨리면 기권이 없던
     * 것처럼 판정된다** — 즉 깨진 사실이 잘못된 승패로 덮인다. 배치는 이 예외를 건별로 세고
     * 넘어가므로(`ChallengeJudgementRunner`) 한 건이 나머지를 막지도 않는다.
     */
    private fun Challenge.forfeitOf(): Forfeit? = when (forfeitedBy) {
        null -> null
        challengerId -> Forfeit.CHALLENGER
        opponentId -> Forfeit.OPPONENT
        else -> error("당사자가 아닌 사람이 기권자로 기록돼 있다 (challengeId=$id, forfeitedBy=$forfeitedBy)")
    }

    private fun recordOf(userId: Long): UserRecord =
        userRecordRepository.findByUserId(userId) ?: UserRecord.empty(userId)

    private fun saveFriendRecord(userId: Long, friendId: Long, outcome: Outcome) {
        // 🔴 기존 행을 먼저 조회해야 한다. 못 찾고 새 행을 INSERT 하면
        //    uq_friend_records_user_friend 에 걸려 판정 트랜잭션 전체가 롤백된다.
        val current = friendRecordRepository.findByUserIdAndFriendId(userId, friendId)
            ?: FriendRecord.empty(userId, friendId)
        friendRecordRepository.save(current.applying(outcome))
    }
}
