package com.lwg.challenge.infra.auth

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
 * users 테이블 JPA 매핑. V1__init.sql 스키마와 1:1.
 *
 * - kotlin-jpa 플러그인이 no-arg 생성자를 자동 생성 → 별도 보조 생성자 불필요.
 * - 필드는 `var` — JPA가 필드 단위로 값을 채우거나 갱신하며, upsert 시 기존 엔티티를 갱신해야 하므로.
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
        id = id ?: error("UserEntity.id is null — not persisted yet"),
        kakaoId = kakaoId,
        nickname = nickname,
        profileImageUrl = profileImageUrl,
        phoneNumber = phoneNumber,
        phoneVerified = phoneVerified,
        fcmToken = fcmToken,
        status = runCatching { UserStatus.valueOf(status) }.getOrDefault(UserStatus.ACTIVE),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
