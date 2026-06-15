package com.lwg.challenge.domain.verification

import java.time.LocalDateTime

/**
 * 챌린지 인증(미션 수행 사진) 도메인 모델.
 *
 * 1 (challengeId, userId) → 1 row (UNIQUE 제약 — V1__init.sql).
 *
 * V1 에선 `photo_url` / `verified_at` 이 NOT NULL 이었으나, "아직 인증 안 함" 상태(PENDING)를 표현할 수 없어
 * V4 마이그레이션에서 nullable 화하고 `status` 컬럼을 추가했다.
 *
 * row 자체가 없으면 PENDING 으로 간주한다 (HomeService 가 LEFT 매핑).
 */
data class Verification(
    val id: Long?,
    val challengeId: Long,
    val userId: Long,
    val status: VerificationStatus,
    val photoUrl: String?,
    val verifiedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
)

/**
 * 모바일과 정확히 일치하는 세 값.
 * - PENDING : 아직 인증 안 함 (또는 row 자체 부재)
 * - VERIFIED: 미션 인증 완료
 * - FAILED  : 시간 내 인증 실패 / 거부 등
 */
enum class VerificationStatus {
    PENDING,
    VERIFIED,
    FAILED,
}
