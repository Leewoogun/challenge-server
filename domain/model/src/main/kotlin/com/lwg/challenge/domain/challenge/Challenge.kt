package com.lwg.challenge.domain.challenge

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 챌린지 도메인 모델. JPA 무관.
 *
 * V1__init.sql 의 `challenges` 테이블과 1:1 매핑된다.
 *
 * - `id == null` ⇒ 신규.
 * - `result`는 챌린지가 COMPLETED 되기 전까지 null.
 *
 * `deadline` 은 DB 의 `TIMESTAMP` (no tz). 백엔드는 UTC 로 간주하여 저장/응답한다.
 */
data class Challenge(
    val id: Long?,
    val challengerId: Long,
    val opponentId: Long,
    val challengerMission: String,
    val opponentMission: String,
    val betContent: String,
    val challengeDate: LocalDate,
    val deadline: LocalDateTime,
    val status: ChallengeStatus,
    val result: ChallengeResult?,
    val createdAt: LocalDateTime,
    val completedAt: LocalDateTime?,
)

enum class ChallengeStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    CONTRACT_SIGNING,
    IN_PROGRESS,
    COMPLETED,
    EXPIRED,
}

enum class ChallengeResult {
    CHALLENGER_WIN,
    OPPONENT_WIN,
    DRAW,
    BOTH_LOSE,
}
