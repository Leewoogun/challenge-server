package com.lwg.challenge.infra.entity.auth

import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * users 테이블 JPA 매핑. V1__init.sql + V3 (refresh token rotation) 스키마.
 *
 * - kotlin-jpa 플러그인이 no-arg 생성자를 자동 생성.
 * - 필드는 var — JPA 가 필드 단위로 값을 채우거나 갱신.
 */
@Entity
@Table(name = "users")
class UserEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "kakao_id", nullable = false, unique = true)
    var kakaoId: Long,

    @Column(name = "nickname", nullable = false, length = 50)
    var nickname: String,

    @Column(name = "profile_image_url", columnDefinition = "TEXT")
    var profileImageUrl: String? = null,

    @Column(name = "phone_number", length = 64, unique = true)
    var phoneNumber: String? = null,

    @Column(name = "phone_verified", nullable = false)
    var phoneVerified: Boolean = false,

    @Column(name = "fcm_token", columnDefinition = "TEXT")
    var fcmToken: String? = null,

    @Column(name = "status", nullable = false, length = 20)
    var status: String = UserStatus.ACTIVE.name,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "refresh_token_hash", length = 64)
    var refreshTokenHash: String? = null,

    @Column(name = "refresh_token_issued_at")
    var refreshTokenIssuedAt: LocalDateTime? = null,
) {

    @PrePersist
    fun prePersist() {
        val now = LocalDateTime.now()
        if (createdAt == LocalDateTime.MIN) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = LocalDateTime.now()
    }

    fun toDomain(): User = User(
        id = id,
        kakaoId = kakaoId,
        nickname = nickname,
        profileImageUrl = profileImageUrl,
        phoneNumber = phoneNumber,
        phoneVerified = phoneVerified,
        fcmToken = fcmToken,
        status = runCatching { UserStatus.valueOf(status) }.getOrDefault(UserStatus.ACTIVE),
        createdAt = createdAt,
        updatedAt = updatedAt,
        refreshTokenHash = refreshTokenHash,
        refreshTokenIssuedAt = refreshTokenIssuedAt,
    )

    companion object {
        fun fromDomain(user: User): UserEntity = UserEntity(
            id = user.id,
            kakaoId = user.kakaoId,
            nickname = user.nickname,
            profileImageUrl = user.profileImageUrl,
            phoneNumber = user.phoneNumber,
            phoneVerified = user.phoneVerified,
            fcmToken = user.fcmToken,
            status = user.status.name,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
            refreshTokenHash = user.refreshTokenHash,
            refreshTokenIssuedAt = user.refreshTokenIssuedAt,
        )
    }
}
