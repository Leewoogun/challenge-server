package com.lwg.challenge.service.user

import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 사용자 본인 정보 조회 (spec-user-info §4.3).
 *
 * 토큰의 userId 로 단일 row 조회만 담당. 마이그레이션 0건 (V1 스키마 그대로).
 */
@Service
class UserService(
    private val userRepository: UserRepository,
) {

    /**
     * 본인 정보 조회. 토큰의 userId 가 DB 에 없으면(회원탈퇴/삭제) 401.
     */
    @Transactional(readOnly = true)
    fun getMe(me: Long): User =
        userRepository.findById(me) ?: throw UnauthorizedException("user not found")
}
