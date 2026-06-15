package com.lwg.challenge.infra.entity.userrecord

import com.lwg.challenge.domain.userrecord.UserRecord
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * `user_stats` 테이블 JPA 매핑.
 *
 * 테이블명은 V1__init.sql 그대로(`user_stats`). 도메인/엔티티 네이밍만 `UserRecord` 로 정렬.
 * PK 가 user_id (users.id 참조, @GeneratedValue 없음).
 */
@Entity
@Table(name = "user_stats")
class UserRecordEntity(
    @Id
    @Column(name = "user_id")
    var userId: Long,

    @Column(name = "total_challenges", nullable = false)
    var totalChallenges: Int = 0,

    @Column(name = "wins", nullable = false)
    var wins: Int = 0,

    @Column(name = "losses", nullable = false)
    var losses: Int = 0,

    @Column(name = "draws", nullable = false)
    var draws: Int = 0,

    @Column(name = "current_streak", nullable = false)
    var currentStreak: Int = 0,

    @Column(name = "max_streak", nullable = false)
    var maxStreak: Int = 0,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {

    @PreUpdate
    fun preUpdate() {
        updatedAt = LocalDateTime.now()
    }

    fun toDomain(): UserRecord = UserRecord(
        userId = userId,
        totalChallenges = totalChallenges,
        wins = wins,
        losses = losses,
        draws = draws,
        currentStreak = currentStreak,
        maxStreak = maxStreak,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(r: UserRecord): UserRecordEntity = UserRecordEntity(
            userId = r.userId,
            totalChallenges = r.totalChallenges,
            wins = r.wins,
            losses = r.losses,
            draws = r.draws,
            currentStreak = r.currentStreak,
            maxStreak = r.maxStreak,
            updatedAt = r.updatedAt,
        )
    }
}
