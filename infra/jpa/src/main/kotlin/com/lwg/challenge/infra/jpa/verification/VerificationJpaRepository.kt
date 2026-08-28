package com.lwg.challenge.infra.jpa.verification

import com.lwg.challenge.infra.entity.verification.VerificationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * verifications 테이블 CRUD.
 *
 * findAllByChallengeIdIn / findAllByUserId 는 Spring Data 가 메서드명만 보고 쿼리 생성.
 */
interface VerificationJpaRepository : JpaRepository<VerificationEntity, Long> {
    fun findAllByChallengeIdIn(challengeIds: Collection<Long>): List<VerificationEntity>

    /**
     * 한 사용자의 인증 row 전부. 탈퇴 시 지울 사진 key 를 모으는 데 쓴다 (mypage T-B2).
     *
     * `userId` 는 `@ManyToOne` 연관이 아니라 plain `Long` 컬럼이라 (`VerificationEntity` 참조)
     * derived query 가 그대로 성립한다.
     */
    fun findAllByUserId(userId: Long): List<VerificationEntity>

    /**
     * 한 사용자의 모든 인증 사진 key 를 NULL 로 (mypage T-B2 — 회원탈퇴).
     *
     * 🔴 **`status` / `verified_at` 은 손대지 않는다.** 인증했다는 사실은 상대의 챌린지 결과를
     * 성립시키는 근거라 보존하고, 지우는 것은 사진(개인정보)뿐이다. 근거는
     * `VerificationRepository.clearPhotoKeysByUserId` KDoc 참조.
     *
     * ⚠️ 컬럼 이름은 `photo_url` 이지만 담긴 값은 **URL 이 아니라 key** 다
     * (`V9__verification_photo_key_recomment.sql`).
     */
    @Modifying
    @Query(
        """
        UPDATE VerificationEntity v
           SET v.photoUrl = null
         WHERE v.userId = :userId
        """,
    )
    fun clearPhotoKeysByUserId(@Param("userId") userId: Long): Int
}
