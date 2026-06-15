package com.lwg.challenge.infra.entity.challenge

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeResult
import com.lwg.challenge.domain.challenge.ChallengeStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * `challenges` 테이블 JPA 매핑. V1__init.sql 스키마 그대로.
 *
 * `status` / `result` 는 VARCHAR enum name 으로 저장.
 */
@Entity
@Table(name = "challenges")
class ChallengeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "challenger_id", nullable = false)
    var challengerId: Long,

    @Column(name = "opponent_id", nullable = false)
    var opponentId: Long,

    @Column(name = "challenger_mission", nullable = false, columnDefinition = "TEXT")
    var challengerMission: String,

    @Column(name = "opponent_mission", nullable = false, columnDefinition = "TEXT")
    var opponentMission: String,

    @Column(name = "bet_content", nullable = false, columnDefinition = "TEXT")
    var betContent: String,

    @Column(name = "challenge_date", nullable = false)
    var challengeDate: LocalDate,

    @Column(name = "deadline", nullable = false)
    var deadline: LocalDateTime,

    @Column(name = "status", nullable = false, length = 20)
    var status: String,

    @Column(name = "result", length = 20)
    var result: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,
) {

    fun toDomain(): Challenge = Challenge(
        id = id,
        challengerId = challengerId,
        opponentId = opponentId,
        challengerMission = challengerMission,
        opponentMission = opponentMission,
        betContent = betContent,
        challengeDate = challengeDate,
        deadline = deadline,
        status = runCatching { ChallengeStatus.valueOf(status) }.getOrDefault(ChallengeStatus.PENDING),
        result = result?.let { runCatching { ChallengeResult.valueOf(it) }.getOrNull() },
        createdAt = createdAt,
        completedAt = completedAt,
    )

    companion object {
        fun fromDomain(c: Challenge): ChallengeEntity = ChallengeEntity(
            id = c.id,
            challengerId = c.challengerId,
            opponentId = c.opponentId,
            challengerMission = c.challengerMission,
            opponentMission = c.opponentMission,
            betContent = c.betContent,
            challengeDate = c.challengeDate,
            deadline = c.deadline,
            status = c.status.name,
            result = c.result?.name,
            createdAt = c.createdAt,
            completedAt = c.completedAt,
        )
    }
}
