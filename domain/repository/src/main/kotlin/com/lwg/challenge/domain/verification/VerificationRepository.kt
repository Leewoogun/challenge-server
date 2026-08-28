package com.lwg.challenge.domain.verification

/**
 * Verification 영속성 추상.
 *
 * challengeId+userId 가 UNIQUE.
 */
interface VerificationRepository {

    /**
     * 주어진 challengeId 목록에 속한 모든 verification row 를 한 번에 조회.
     *
     * home-feed 응답에서 N+1 회피용 — 사용자가 가진 진행 중 챌린지 N 개에 대해 한 SELECT 로
     * 양측(나/상대) verification 을 모두 가져온다.
     */
    fun findAllByChallengeIds(challengeIds: Collection<Long>): List<Verification>

    /** 저장 (테스트 시드/인증 트랜잭션용). */
    fun save(verification: Verification): Verification

    /**
     * 여러 건 일괄 저장 후 즉시 flush.
     *
     * 챌린지 수락 시 양측(challenger / opponent) PENDING row 2건을 **같은 트랜잭션**에서 만들기 위해 사용.
     * `uq_verifications_challenge_user` 유니크 제약이 중복 수락에 대한 안전망 역할을 하는데,
     * flush 를 미루면 그 제약 위반이 service 의 try/catch 밖에서 터진다 (friends 2차 saveAndFlush 선례).
     */
    fun saveAllAndFlush(verifications: Collection<Verification>): List<Verification>

    /**
     * [userId] 의 verification row 전부. 탈퇴 시 **지울 사진 key 를 모으는 데** 쓴다 (mypage T-B2).
     *
     * key 를 NULL 로 밀기 **전에** 읽어야 한다 — 밀고 나면 어떤 파일을 지워야 하는지 알 수 없다.
     */
    fun findAllByUserId(userId: Long): List<Verification>

    /**
     * [userId] 의 모든 verification 의 `photo_url`(=key) 를 NULL 로 (mypage T-B2).
     *
     * 🔴 **`status` / `verified_at` 은 건드리지 않는다.** 탈퇴자가 그날 인증을 했다는 **사실**은
     * 상대의 챌린지 결과를 성립시키는 근거라 보존해야 한다. 지우는 것은 사진(개인정보)뿐이다.
     * 그 결과 `status = VERIFIED` 인데 key 가 없는 row 가 생기며, 이건 정상 상태다 —
     * `VerificationService.view` / `readPhoto` 가 **key 유무로 판단**해 이미 올바르게 동작한다.
     *
     * @return 갱신된 row 수.
     */
    fun clearPhotoKeysByUserId(userId: Long): Int
}
