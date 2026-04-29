package com.lwg.challenge.domain.user

import java.time.LocalDateTime

/**
 * 순수 도메인 모델. JPA / Spring 무관.
 *
 * 영속성 매핑은 `:infra` 모듈의 `UserEntity`가 담당한다.
 * 이 모델은 서비스 계층에서 비즈니스 로직을 다룰 때 사용.
 */
data class User(
    val id: Long,
    val kakaoId: Long,
    val nickname: String,
    val profileImageUrl: String?,
    /** SHA-256(정규화된 번호) hex 문자열. null = 미등록(카카오 scope 동의 거부 등). */
    val phoneNumber: String?,
    /** true = 카카오가 인증한 번호(ADR-0008). */
    val phoneVerified: Boolean,
    val fcmToken: String?,
    val status: UserStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

enum class UserStatus {
    ACTIVE, SUSPENDED, DELETED;
}
