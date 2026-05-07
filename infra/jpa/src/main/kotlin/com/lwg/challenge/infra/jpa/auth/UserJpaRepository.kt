package com.lwg.challenge.infra.jpa.auth

import com.lwg.challenge.infra.entity.auth.UserEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * users 테이블 CRUD. Spring Data JPA. 모듈 외부에 노출되지 않는다 (UserRepositoryImpl 만 사용).
 */
interface UserJpaRepository : JpaRepository<UserEntity, Long> {
    fun findByKakaoId(kakaoId: Long): UserEntity?
}
