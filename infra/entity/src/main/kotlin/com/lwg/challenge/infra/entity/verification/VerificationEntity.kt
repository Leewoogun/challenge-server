package com.lwg.challenge.infra.entity.verification

import com.lwg.challenge.domain.verification.VerdictOutcome
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import com.lwg.challenge.core.time.KstTime
import java.time.LocalDateTime

/**
 * `verifications` 테이블 JPA 매핑.
 *
 * V1 스키마: id / challenge_id / user_id / photo_url NOT NULL / verified_at NOT NULL + UNIQUE(challenge_id, user_id).
 * V4 마이그레이션에서 `status` 컬럼 추가, photo_url/verified_at nullable 화, created_at 추가.
 * V13 에서 AI 판별 로그 4컬럼 추가 (ai-verification).
 *
 * status: PENDING / VERIFIED / FAILED.
 *
 * 🔴 `ddl-auto=validate` 라 **컬럼명이 V13 과 한 글자라도 다르면 서버가 기동하지 않는다.**
 * 그게 이 프로젝트에서 매핑 누락이 조용히 지나가지 않는 이유다.
 */
@Entity
@Table(name = "verifications")
class VerificationEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "challenge_id", nullable = false)
    var challengeId: Long,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "status", nullable = false, length = 20)
    var status: String = VerificationStatus.PENDING.name,

    @Column(name = "photo_url", columnDefinition = "TEXT")
    var photoUrl: String? = null,

    @Column(name = "verified_at")
    var verifiedAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = KstTime.now(),

    // ─── AI 판별 로그 (V13, ai-verification) ─────────────────────
    // 전부 nullable 이고 그게 정상 상태다 — 미제출·기권·fail-open 건은 영구히 NULL 로 남는다.

    @Column(name = "ai_verdict", length = 20)
    var aiVerdict: String? = null,

    @Column(name = "ai_confidence")
    var aiConfidence: Double? = null,

    @Column(name = "ai_reason", columnDefinition = "TEXT")
    var aiReason: String? = null,

    @Column(name = "ai_checked_at")
    var aiCheckedAt: LocalDateTime? = null,
) {

    fun toDomain(): Verification = Verification(
        id = id,
        challengeId = challengeId,
        userId = userId,
        status = runCatching { VerificationStatus.valueOf(status) }.getOrDefault(VerificationStatus.PENDING),
        photoUrl = photoUrl,
        verifiedAt = verifiedAt,
        createdAt = createdAt,
        // 🔴 `status` 와 달리 **알 수 없는 값을 기본값으로 접지 않는다.** status 는 3종 중
        //    하나로 떨어져야 화면이 그려지지만, 판정 로그는 읽을 수 없으면 "판정 없음" 이
        //    정직한 답이다. PASS 로 접으면 통과시킨 적 없는 건이 통과한 것으로 보인다.
        aiVerdict = aiVerdict?.let { raw -> runCatching { VerdictOutcome.valueOf(raw) }.getOrNull() },
        aiConfidence = aiConfidence,
        aiReason = aiReason,
        aiCheckedAt = aiCheckedAt,
    )

    companion object {
        fun fromDomain(v: Verification): VerificationEntity = VerificationEntity(
            id = v.id,
            challengeId = v.challengeId,
            userId = v.userId,
            status = v.status.name,
            photoUrl = v.photoUrl,
            verifiedAt = v.verifiedAt,
            createdAt = v.createdAt,
            aiVerdict = v.aiVerdict?.name,
            aiConfidence = v.aiConfidence,
            aiReason = v.aiReason,
            aiCheckedAt = v.aiCheckedAt,
        )
    }
}
