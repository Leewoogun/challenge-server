package com.lwg.challenge.infra.jpa.auth

import com.lwg.challenge.infra.entity.auth.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

/**
 * users 테이블 CRUD. Spring Data JPA. 모듈 외부에 노출되지 않는다 (UserRepositoryImpl 만 사용).
 */
interface UserJpaRepository : JpaRepository<UserEntity, Long> {

    fun findByKakaoId(kakaoId: Long): UserEntity?

    /**
     * refresh_token_hash / refresh_token_issued_at 두 컬럼만 갱신.
     *
     * 영속 컨텍스트를 거치지 않고 직접 UPDATE — rotation 같은 핀포인트 갱신에 dirty checking 보다 명확.
     * `updated_at` 트리거(@PreUpdate)는 동작하지 않으므로 의도적으로 건드리지 않는다 (rotation 은 logical 갱신 아님).
     */
    @Modifying
    @Query(
        """
        UPDATE UserEntity u
           SET u.refreshTokenHash = :hash,
               u.refreshTokenIssuedAt = :issuedAt
         WHERE u.id = :userId
        """,
    )
    fun updateRefreshTokenHash(
        @Param("userId") userId: Long,
        @Param("hash") hash: String?,
        @Param("issuedAt") issuedAt: LocalDateTime?,
    ): Int
}
