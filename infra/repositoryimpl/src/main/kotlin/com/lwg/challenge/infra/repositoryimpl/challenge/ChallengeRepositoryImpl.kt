package com.lwg.challenge.infra.repositoryimpl.challenge

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.ReceivedChallenge
import com.lwg.challenge.infra.entity.challenge.ChallengeEntity
import com.lwg.challenge.infra.jpa.challenge.ChallengeJpaRepository
import org.springframework.stereotype.Component
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * `ChallengeRepository` 의 JPA 구현.
 *
 * `findReceivedPending` 만 native query 라 Object[] → 도메인 매핑 책임이 여기에 있다.
 * 컬럼 순서가 `ChallengeJpaRepository.findReceivedPending` 의 SELECT 절과 1:1 로 맞아야 한다
 * (`FriendshipRepositoryImpl` 과 동일한 규약).
 */
@Component
class ChallengeRepositoryImpl(
    private val jpa: ChallengeJpaRepository,
) : ChallengeRepository {

    override fun findActiveByUser(userId: Long, statuses: Collection<ChallengeStatus>): List<Challenge> {
        if (statuses.isEmpty()) return emptyList()
        return jpa.findActiveByUser(userId, statuses.map { it.name }).map { it.toDomain() }
    }

    override fun findRecentlyCompletedByUser(userId: Long, since: LocalDateTime): List<Challenge> =
        jpa.findRecentlyCompletedByUser(userId, since).map { it.toDomain() }

    override fun findCompletedHistoryByUserInMonth(
        userId: Long,
        monthStart: LocalDate,
        nextMonthStart: LocalDate,
    ): List<Challenge> =
        jpa.findCompletedHistoryByUserInMonth(userId, monthStart, nextMonthStart).map { it.toDomain() }

    override fun findEarliestCompletedChallengeDate(userId: Long): LocalDate? =
        jpa.findEarliestCompletedChallengeDate(userId)

    override fun findById(id: Long): Challenge? =
        jpa.findById(id).map { it.toDomain() }.orElse(null)

    override fun findDueByStatus(statuses: Collection<ChallengeStatus>, now: LocalDateTime): List<Challenge> {
        if (statuses.isEmpty()) return emptyList()
        return jpa.findDueByStatus(statuses.map { it.name }, now).map { it.toDomain() }
    }

    override fun findReceivedPending(opponentId: Long, now: LocalDateTime): List<ReceivedChallenge> {
        return jpa.findReceivedPending(opponentId, now).map { row ->
            // 컬럼 순서: challenge_id, challenger_id, nickname, profile_image_url,
            //            challenger_mission, bet_content, challenge_date, deadline, created_at
            ReceivedChallenge(
                challengeId = (row[0] as Number).toLong(),
                challengerId = (row[1] as Number).toLong(),
                challengerNickname = row[2] as String,
                challengerProfileImageUrl = row[3] as String?,
                challengerMission = row[4] as String,
                betContent = row[5] as String,
                challengeDate = toLocalDate(row[6]),
                deadline = toLocalDateTime(row[7]),
                createdAt = toLocalDateTime(row[8]),
            )
        }
    }

    override fun findConflicting(
        userA: Long,
        userB: Long,
        challengeDate: LocalDate,
        statuses: Collection<ChallengeStatus>,
    ): List<Challenge> {
        if (statuses.isEmpty()) return emptyList()
        return jpa.findConflicting(userA, userB, challengeDate, statuses.map { it.name })
            .map { it.toDomain() }
    }

    override fun save(challenge: Challenge): Challenge =
        jpa.save(ChallengeEntity.fromDomain(challenge)).toDomain()

    override fun saveAndFlush(challenge: Challenge): Challenge =
        jpa.saveAndFlush(ChallengeEntity.fromDomain(challenge)).toDomain()

    override fun deleteById(id: Long) {
        jpa.deleteById(id)
    }

    override fun deletePendingSentBy(userId: Long): Int =
        jpa.deletePendingSentBy(userId)

    /** JDBC 드라이버가 `java.sql.Date` 로 줄 수도, 이미 `LocalDate` 로 줄 수도 있어 둘 다 받는다. */
    private fun toLocalDate(value: Any?): LocalDate = when (value) {
        is LocalDate -> value
        is Date -> value.toLocalDate()
        else -> error("기대하지 않은 날짜 타입: ${value?.javaClass}")
    }

    private fun toLocalDateTime(value: Any?): LocalDateTime = when (value) {
        is LocalDateTime -> value
        is Timestamp -> value.toLocalDateTime()
        else -> error("기대하지 않은 시간 타입: ${value?.javaClass}")
    }
}
