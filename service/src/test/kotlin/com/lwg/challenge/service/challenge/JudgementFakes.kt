package com.lwg.challenge.service.challenge

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.ReceivedChallenge
import com.lwg.challenge.domain.friendrecord.FriendRecord
import com.lwg.challenge.domain.friendrecord.FriendRecordRepository
import com.lwg.challenge.domain.userrecord.UserRecord
import com.lwg.challenge.domain.userrecord.UserRecordRepository
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationRepository
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 판정 배치 테스트가 공유하는 in-memory fake 들 (challenge-result T-B1·T-B2·T-B3).
 *
 * ## 왜 별도 파일인가
 *
 * [ChallengeJudgementServiceTest] 와 [ChallengeJudgementRunnerTest] 가 **같은 저장소 상태**를
 * 봐야 한다 — Runner 테스트는 진짜 [ChallengeJudgementService] 를 돌려서 "판정이 실제로
 * 일어났는가" 를 확인하기 때문이다. 파일마다 `private` fake 를 두면 그게 불가능하다.
 *
 * ⚠️ 이름이 `FakeChallengeRepository` 가 아닌 이유: 같은 패키지의
 * `ChallengeCommandServiceTest` 가 이미 그 이름을 쓰고 있다 (top-level `private` 라도 JVM
 * 클래스명은 패키지 단위로 충돌한다).
 *
 * ⚠️ **JPA 를 흉내내지 않는다.** UNIQUE 제약도 flush 시점도 여기엔 없다. 여기서 알 수 있는 것은
 * *서비스가 무엇을 읽고 무엇을 저장하는가* 뿐이다 (`VerificationServiceTest` 의 fake 와 같은 한계).
 */
internal class FakeChallenges : ChallengeRepository {
    private val store = LinkedHashMap<Long, Challenge>()

    /** 저장 호출 횟수. *"멱등 가드가 저장을 0 건으로 막았다"* 를 단언하는 데 쓴다. */
    var saveCount = 0
        private set

    /**
     * [findById] 가 예외를 던질 id 들.
     *
     * Runner 의 *"한 건이 터져도 나머지를 판정한다"* 를 검증하려면 판정 중 하나가 실패해야 하는데,
     * [ChallengeJudgementService] 를 mock 으로 바꾸는 대신 **진짜 서비스를 그대로 두고 저장소가
     * 터지게** 한다 — 실제 실패 경로(DB 오류)에 더 가깝고, 서비스의 실제 동작도 함께 검증된다.
     */
    val failOnFindById = mutableSetOf<Long>()

    fun put(c: Challenge) {
        store[c.id!!] = c
    }

    fun all(): List<Challenge> = store.values.toList()

    fun resetSaveCount() {
        saveCount = 0
    }

    override fun findById(id: Long): Challenge? {
        if (id in failOnFindById) error("의도적으로 터뜨린 조회 (challengeId=$id)")
        return store[id]
    }

    /** 실제 구현과 같은 경계(`<=`)와 정렬(deadline ASC)을 지킨다 — 어긋나면 테스트가 거짓말을 한다. */
    override fun findDueByStatus(statuses: Collection<ChallengeStatus>, now: LocalDateTime): List<Challenge> {
        if (statuses.isEmpty()) return emptyList()
        return store.values
            .filter { it.status in statuses && it.deadline <= now }
            .sortedBy { it.deadline }
    }

    override fun findActiveByUser(userId: Long, statuses: Collection<ChallengeStatus>): List<Challenge> =
        store.values.filter { (it.challengerId == userId || it.opponentId == userId) && it.status in statuses }

    /** 판정 배치는 쓰지 않지만(홈 목록 전용), 구현과 같은 경계(`>`)·정렬을 지켜 둔다. */
    override fun findRecentlyCompletedByUser(userId: Long, since: LocalDateTime): List<Challenge> =
        store.values
            .filter { (it.challengerId == userId || it.opponentId == userId) }
            .filter { it.status == ChallengeStatus.COMPLETED && it.completedAt != null && it.completedAt!! > since }
            .sortedByDescending { it.completedAt }

    override fun findReceivedPending(opponentId: Long, now: LocalDateTime): List<ReceivedChallenge> = emptyList()

    override fun findConflicting(
        userA: Long,
        userB: Long,
        challengeDate: LocalDate,
        statuses: Collection<ChallengeStatus>,
    ): List<Challenge> = emptyList()

    override fun save(challenge: Challenge): Challenge {
        saveCount++
        put(challenge)
        return challenge
    }

    override fun saveAndFlush(challenge: Challenge): Challenge = save(challenge)

    override fun deleteById(id: Long) {
        store.remove(id)
    }
}

internal class FakeVerifications : VerificationRepository {
    private val store = mutableListOf<Verification>()
    private var nextId = 1L

    /** 저장 호출 횟수 — *"이미 FAILED 인 row 를 다시 UPDATE 하지 않는다"* 를 단언하는 데 쓴다. */
    var saveCount = 0
        private set

    fun all(): List<Verification> = store.toList()

    override fun findAllByChallengeIds(challengeIds: Collection<Long>): List<Verification> =
        store.filter { it.challengeId in challengeIds }

    override fun save(verification: Verification): Verification {
        saveCount++
        val saved = verification.copy(id = verification.id ?: nextId++)
        store.removeAll { it.id == saved.id }
        store.add(saved)
        return saved
    }

    override fun saveAllAndFlush(verifications: Collection<Verification>): List<Verification> =
        verifications.map { save(it) }
}

internal class FakeUserRecords : UserRecordRepository {
    private val store = LinkedHashMap<Long, UserRecord>()

    /** 비어 있으면 *"전적 저장이 0 건이었다"* — `expire` 와 멱등 가드가 그걸 요구한다. */
    fun all(): List<UserRecord> = store.values.toList()

    override fun findByUserId(userId: Long): UserRecord? = store[userId]

    override fun save(record: UserRecord): UserRecord {
        store[record.userId] = record
        return record
    }
}

internal class FakeFriendRecords : FriendRecordRepository {
    private val store = LinkedHashMap<Pair<Long, Long>, FriendRecord>()
    private var nextId = 1L

    fun all(): List<FriendRecord> = store.values.toList()

    override fun findByUserIdAndFriendId(userId: Long, friendId: Long): FriendRecord? = store[userId to friendId]

    override fun save(record: FriendRecord): FriendRecord {
        // BIGSERIAL 흉내 — 방향(user_id, friend_id)이 키다. UNIQUE 제약 위반은 재현하지 않는다.
        val saved = record.copy(id = record.id ?: nextId++)
        store[saved.userId to saved.friendId] = saved
        return saved
    }
}
