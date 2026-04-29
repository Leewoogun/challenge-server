package com.lwg.challenge.infra.auth

import org.springframework.data.jpa.repository.JpaRepository

/**
 * users 테이블 CRUD. Spring Data JPA.
 *
 * - kakao_id는 UNIQUE 제약 → 조회 키로 사용.
 * - 동시 요청으로 같은 kakao_id가 INSERT되면 DB UNIQUE 위반(DataIntegrityViolationException)으로
 *   한쪽이 실패한다. 서비스 계층에서 재시도 1회(find 후 업데이트로 전환)가 필요하지만,
 *   초기 MVP에선 업스트림 모바일이 중복 로그인 요청을 거의 안 보낸다고 가정하고 단순 구현.
 */
interface UserJpaRepository : JpaRepository<UserEntity, Long> {
    fun findByKakaoId(kakaoId: Long): UserEntity?
}
