package com.lwg.challenge.infra.repositoryimpl.auth

import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.infra.entity.auth.UserEntity
import com.lwg.challenge.infra.jpa.auth.UserJpaRepository
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * `UserRepository` 도메인 인터페이스의 JPA 구현체.
 *
 * `UserJpaRepository.save()` 는 entity.id == null 이면 INSERT, 있으면 MERGE 동작 (Spring Data JPA 기본).
 * `User.id == null` 이 그대로 `UserEntity.id == null` 로 매핑되므로 정상 작동.
 */
@Component
class UserRepositoryImpl(
    private val jpa: UserJpaRepository,
) : UserRepository {

    override fun findByKakaoId(kakaoId: Long): User? =
        jpa.findByKakaoId(kakaoId)?.toDomain()

    override fun findById(id: Long): User? =
        jpa.findById(id).map { it.toDomain() }.orElse(null)

    override fun save(user: User): User =
        jpa.save(UserEntity.fromDomain(user)).toDomain()

    override fun updateRefreshTokenHash(userId: Long, hash: String?, issuedAt: LocalDateTime?) {
        jpa.updateRefreshTokenHash(userId, hash, issuedAt)
    }
}
