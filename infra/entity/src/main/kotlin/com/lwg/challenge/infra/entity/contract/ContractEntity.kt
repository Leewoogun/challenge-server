package com.lwg.challenge.infra.entity.contract

import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.contract.Contract
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * `contracts` 테이블 JPA 매핑. V1 스키마 + V7(`*_signature_url` → `*_signature_data` rename).
 *
 * 서명 컬럼은 **URL 이 아니라 벡터 스트로크 JSON** 이다. 컬럼 주석에 형식이 있다 (V7).
 */
@Entity
@Table(name = "contracts")
class ContractEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "challenge_id", nullable = false, unique = true)
    var challengeId: Long,

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(name = "challenger_signature_data", columnDefinition = "TEXT")
    var challengerSignatureData: String? = null,

    @Column(name = "challenger_signed_at")
    var challengerSignedAt: LocalDateTime? = null,

    @Column(name = "opponent_signature_data", columnDefinition = "TEXT")
    var opponentSignatureData: String? = null,

    @Column(name = "opponent_signed_at")
    var opponentSignedAt: LocalDateTime? = null,

    @Column(name = "is_finalized", nullable = false)
    var isFinalized: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = KstTime.now(),
) {

    fun toDomain(): Contract = Contract(
        id = id,
        challengeId = challengeId,
        content = content,
        challengerSignatureData = challengerSignatureData,
        challengerSignedAt = challengerSignedAt,
        opponentSignatureData = opponentSignatureData,
        opponentSignedAt = opponentSignedAt,
        isFinalized = isFinalized,
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(c: Contract): ContractEntity = ContractEntity(
            id = c.id,
            challengeId = c.challengeId,
            content = c.content,
            challengerSignatureData = c.challengerSignatureData,
            challengerSignedAt = c.challengerSignedAt,
            opponentSignatureData = c.opponentSignatureData,
            opponentSignedAt = c.opponentSignedAt,
            isFinalized = c.isFinalized,
            createdAt = c.createdAt,
        )
    }
}
