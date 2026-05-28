package com.lwg.challenge.domain.user

import java.time.LocalDateTime

/**
 * 사용자 영속성 추상.
 *
 * 구현체는 `:infra:repositoryimpl` 의 `UserRepositoryImpl` 가 담당하며 Spring Data JPA 를 위임한다.
 * 도메인/서비스 계층은 이 인터페이스만 보고 JPA·Entity 를 모른다.
 */
interface UserRepository {

    /**
     * Kakao ID 로 사용자 조회. 없으면 null.
     */
    fun findByKakaoId(kakaoId: Long): User?

    /**
     * 내부 id 로 사용자 조회. 없으면 null.
     */
    fun findById(id: Long): User?

    /**
     * 사용자 저장.
     *
     * - `user.id == null` ⇒ 신규 INSERT 후 id 가 채워진 User 반환.
     * - `user.id != null` ⇒ MERGE (기존 row 갱신 후 갱신된 User 반환).
     */
    fun save(user: User): User

    /**
     * 현재 유효한 refresh token 의 sha256 hex 를 DB 에 기록한다.
     * 같은 user 의 이전 hash 는 덮어씌워지며, 이로 인해 이전 refresh 가 자동 무효화된다 (rotation).
     *
     * hash 가 null 이면 사용자의 refresh 를 강제 무효화 (로그아웃 등에 사용 — 본 PR 에서는 사용처 없음).
     *
     * @param userId 대상 사용자 id
     * @param hash sha256(rawRefreshToken) 의 64자 lower-hex. null 이면 무효화.
     * @param issuedAt rotation 시각. hash 가 null 일 때는 같이 null 권장.
     */
    fun updateRefreshTokenHash(userId: Long, hash: String?, issuedAt: LocalDateTime?)
}
