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
     * 여러 id 를 한 번에 조회하여 id → User 맵으로 반환.
     *
     * home-feed 응답에서 상대방(opponent) 닉네임 N 명을 한 SELECT 로 가져오기 위해 사용.
     * 빈 컬렉션을 넘기면 빈 맵을 반환.
     */
    fun findAllByIds(ids: Collection<Long>): Map<Long, User>

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

    /**
     * 같은 FCM 토큰을 쥐고 있는 **다른** 사용자들의 `fcm_token` 을 NULL 로 밀어낸다.
     *
     * 🔴 **한 기기가 두 계정의 알림을 받는 것을 막는 유일한 장치다** (push-fcm 계약 §0.2).
     * `users.fcm_token` 에 UNIQUE 제약이 없으므로 DB 가 이걸 대신 보장해 주지 않는다.
     *
     * logout([updateFcmToken] 에 null) 이 대부분의 계정 전환을 덮지만, 앱 삭제·강제 종료·토큰 만료로
     * logout 이 아예 호출되지 않는 경로가 남는다. 그래서 **등록 시점에도** 방어한다.
     *
     * @param fcmToken 이번에 등록되는 토큰
     * @param exceptUserId 이 사용자(=등록 주체)는 건드리지 않는다
     * @return NULL 로 밀린 row 수. 정상 흐름에서는 0 이고, 계정 전환 직후에만 1 이 나온다.
     */
    fun clearFcmTokenOwnedByOthers(fcmToken: String, exceptUserId: Long): Int

    /**
     * 특정 사용자의 `fcm_token` 만 갱신한다. null 이면 제거(로그아웃).
     *
     * @return 갱신된 row 수. 0 이면 해당 user 가 DB 에 없다는 뜻이다
     *         (탈퇴·삭제된 계정의 access token 이 아직 유효한 경우).
     */
    fun updateFcmToken(userId: Long, fcmToken: String?): Int

    /**
     * 회원탈퇴 익명화 (mypage T-B2). **row 를 지우지 않고** 개인정보만 지운다.
     *
     * row 를 남기는 이유는 상대의 챌린지·계약서·서명·결과가 FK 로 이 row 를 가리키기 때문이다.
     * 지우면 **탈퇴하지 않은 사람의 기록까지 무너진다.**
     *
     * 갱신하는 컬럼은 정확히 이것들이다 (더도 덜도 아니다):
     *
     * | 컬럼 | 값 |
     * |---|---|
     * | `kakao_id` | `NULL` |
     * | `nickname` | [anonymizedNickname] |
     * | `profile_image_url` | `NULL` |
     * | `phone_number` | `NULL` |
     * | `phone_verified` | `false` |
     * | `fcm_token` | `NULL` |
     * | `refresh_token_hash` | `NULL` |
     * | `refresh_token_issued_at` | `NULL` |
     * | `status` | `'DELETED'` |
     * | `updated_at` | [now] |
     *
     * `id` / `created_at` 은 건드리지 않는다.
     *
     * 🔴 **`nickname` 은 NOT NULL 을 유지한다.** 상대의 지난 챌린지 상세·보관함·홈 카드가 이
     * 값을 non-null 로 받으므로, NULL 로 만들면 그 응답들이 전부 깨진다.
     *
     * ⚠️ **벌크 UPDATE 라 `@PreUpdate` 가 돌지 않는다** — 그래서 [now] 를 인자로 받아
     * `updated_at` 을 직접 쓴다. [updateRefreshTokenHash] / [updateFcmToken] 이 같은 이유로
     * `updated_at` 을 **안** 건드리는 것과 정확히 반대 지점이다: 그쪽은 기기 상태 갱신이라
     * 사용자 프로필의 논리적 변경이 아니지만, 탈퇴는 **가장 논리적인 변경**이라 시각이 남아야 한다.
     *
     * @param now 익명화 시각 (KST 벽시계 — ADR-0010)
     * @return 갱신된 row 수. 0 이면 해당 user 가 DB 에 없다는 뜻이다.
     */
    fun anonymizeForWithdrawal(userId: Long, anonymizedNickname: String, now: LocalDateTime): Int
}
