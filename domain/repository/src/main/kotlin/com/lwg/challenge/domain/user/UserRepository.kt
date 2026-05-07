package com.lwg.challenge.domain.user

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
     * 사용자 저장.
     *
     * - `user.id == null` ⇒ 신규 INSERT 후 id 가 채워진 User 반환.
     * - `user.id != null` ⇒ MERGE (기존 row 갱신 후 갱신된 User 반환).
     */
    fun save(user: User): User
}
