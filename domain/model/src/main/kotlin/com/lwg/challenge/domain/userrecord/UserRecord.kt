package com.lwg.challenge.domain.userrecord

import java.time.LocalDateTime

/**
 * 사용자 누적 전적(record) 도메인 모델.
 *
 * V1__init.sql 의 `user_stats` 테이블(user_id PK)과 매핑. 테이블명은 호환을 위해 유지.
 *
 * row 가 없는 신규 사용자는 [empty] 로 0 채움.
 */
data class UserRecord(
    val userId: Long,
    val totalChallenges: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val currentStreak: Int,
    val maxStreak: Int,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun empty(userId: Long): UserRecord = UserRecord(
            userId = userId,
            totalChallenges = 0,
            wins = 0,
            losses = 0,
            draws = 0,
            currentStreak = 0,
            maxStreak = 0,
            updatedAt = LocalDateTime.now(),
        )
    }
}
