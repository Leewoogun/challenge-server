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
 * - `opponentMission`은 상대가 수락하기 전까지 null (V5 마이그레이션에서 NOT NULL 완화).
 *   즉 `status == PENDING` 이면 항상 null, `IN_PROGRESS` 이상이면 항상 non-null 이다.
 *
 * `deadline` 은 DB 의 `TIMESTAMP` (no tz). **KST 로 간주**하여 저장/응답한다 (ADR-0010).
 * offset 을 저장하지 않으므로 "이 값은 KST" 라는 약속이 유일한 기준이다 — 시각 취득은 반드시
 * `Clock`(KST) 또는 `KstTime` 을 통해서만 한다.
 *
 * ## 기권 (challenge-forfeit, V12)
 *
 * [forfeitedBy] 하나뿐이다. **기권 시각 컬럼은 만들지 않았다** — 기권은 즉시 `COMPLETED` 라
 * [completedAt] 이 곧 기권 시각이다. 값이 같은 컬럼을 둘 두면 언젠가 갈린다.
 */
data class Challenge(
    val id: Long?,
    val challengerId: Long,
    val opponentId: Long,
    val challengerMission: String,
    val opponentMission: String?,
    val betContent: String,
    val challengeDate: LocalDate,
    val deadline: LocalDateTime,
    val status: ChallengeStatus,
    val result: ChallengeResult?,
    val createdAt: LocalDateTime,
    val completedAt: LocalDateTime?,
    /**
     * 기권한 사람의 userId. **null = 기권 없이 끝났거나 아직 안 끝났다** (challenge-forfeit).
     *
     * 🔴 **기본값을 주지 않았다.** 이 모델의 다른 nullable 필드(`result`/`completedAt`)와 같은
     * 규약이고, 이유도 같다 — 기본값이 있으면 `ChallengeEntity.toDomain` 이 이 필드 매핑을
     * 빠뜨려도 컴파일이 통과하고, 그러면 **기권 사실이 조회할 때마다 조용히 사라진다.**
     */
    val forfeitedBy: Long?,
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
