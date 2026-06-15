package com.lwg.challenge.infra.entity.verification

import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * `verifications` 테이블 JPA 매핑.
 *
 * V1 스키마: id / challenge_id / user_id / photo_url NOT NULL / verified_at NOT NULL + UNIQUE(challenge_id, user_id).
 * V4 마이그레이션에서 `status` 컬럼 추가, photo_url/verified_at nullable 화, created_at 추가.
 *
 * status: PENDING / VERIFIED / FAILED.
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
    var createdAt: LocalDateTime = LocalDateTime.now(),
) {

    fun toDomain(): Verification = Verification(
        id = id,
        challengeId = challengeId,
        userId = userId,
        status = runCatching { VerificationStatus.valueOf(status) }.getOrDefault(VerificationStatus.PENDING),
        photoUrl = photoUrl,
        verifiedAt = verifiedAt,
        createdAt = createdAt,
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
        )
    }
}
