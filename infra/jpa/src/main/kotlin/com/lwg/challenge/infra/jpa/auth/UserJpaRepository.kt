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

    /**
     * 같은 토큰을 쥔 **다른** 사용자의 fcm_token 을 NULL 로 밀어낸다 (push-fcm 계약 §0.2).
     *
     * ⚠️ `u.fcmToken = :fcmToken` 비교는 인덱스가 없는 TEXT 전체 스캔이다. users 규모가
     * 커지면 `fcm_token` 부분 인덱스(`WHERE fcm_token IS NOT NULL`)를 추가할 것.
     * 지금은 row 수가 한 자리라 의미가 없어 마이그레이션을 만들지 않는다.
     */
    @Modifying
    @Query(
        """
        UPDATE UserEntity u
           SET u.fcmToken = null
         WHERE u.fcmToken = :fcmToken
           AND u.id <> :exceptUserId
        """,
    )
    fun clearFcmTokenOwnedByOthers(
        @Param("fcmToken") fcmToken: String,
        @Param("exceptUserId") exceptUserId: Long,
    ): Int

    /**
     * fcm_token 한 컬럼만 갱신. null 이면 제거(로그아웃).
     *
     * `updateRefreshTokenHash` 와 같은 이유로 벌크 UPDATE 를 쓴다 — 영속 컨텍스트를 거치지 않으므로
     * `@PreUpdate` 의 `updated_at` 갱신이 일어나지 않는다. FCM 토큰은 기기 상태이지 사용자 프로필의
     * 논리적 변경이 아니라서 **의도적으로** updated_at 을 건드리지 않는다.
     */
    @Modifying
    @Query(
        """
        UPDATE UserEntity u
           SET u.fcmToken = :fcmToken
         WHERE u.id = :userId
        """,
    )
    fun updateFcmToken(
        @Param("userId") userId: Long,
        @Param("fcmToken") fcmToken: String?,
    ): Int

    /**
     * 회원탈퇴 익명화 — 개인정보 컬럼을 한 번의 UPDATE 로 전부 민다 (mypage T-B2).
     *
     * 컬럼별 근거는 `UserRepository.anonymizeForWithdrawal` KDoc 과
     * `V11__user_withdrawal.sql` 참조.
     *
     * 🔴 `updatedAt` 을 **인자로 받아 직접 쓴다.** 위 두 벌크 UPDATE 와 마찬가지로 영속 컨텍스트를
     * 거치지 않아 `@PreUpdate` 가 돌지 않는데, 그쪽과 달리 여기서는 `updated_at` 이 **반드시**
     * 갱신돼야 한다 — 탈퇴는 기기 상태가 아니라 사용자 프로필의 논리적 변경이고, 언제 익명화됐는지가
     * 남지 않으면 나중에 확인할 방법이 없다.
     *
     * ⚠️ `status` 는 문자열 리터럴이다. `UserEntity.status` 가 enum 이 아니라 `String` 이기 때문이며
     * (`UserStatus.valueOf` 는 `toDomain` 에서 한다), `'DELETED'` 는 `UserStatus.DELETED.name` 과
     * 같아야 한다.
     */
    @Modifying
    @Query(
        """
        UPDATE UserEntity u
           SET u.kakaoId = null,
               u.nickname = :nickname,
               u.profileImageUrl = null,
               u.phoneNumber = null,
               u.phoneVerified = false,
               u.fcmToken = null,
               u.refreshTokenHash = null,
               u.refreshTokenIssuedAt = null,
               u.status = 'DELETED',
               u.updatedAt = :now
         WHERE u.id = :userId
        """,
    )
    fun anonymizeForWithdrawal(
        @Param("userId") userId: Long,
        @Param("nickname") nickname: String,
        @Param("now") now: LocalDateTime,
    ): Int
}
