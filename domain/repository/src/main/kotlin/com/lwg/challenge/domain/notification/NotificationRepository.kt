package com.lwg.challenge.domain.notification

/**
 * 알림 영속성 추상. 구현체는 `:infra:repositoryimpl` 의 `NotificationRepositoryImpl`.
 *
 * 조회는 아직 없다 — 알림 목록 화면이 이번 범위 밖이라 **쌓기만 한다** (spec 비범위 표).
 * 화면이 붙을 때 `findByUserId(...)` 계열이 여기에 추가된다.
 * ([deleteAllByUserId] 는 그 조회가 아니라 **탈퇴 정리**다 — 화면과 무관하다.)
 */
interface NotificationRepository {

    /** 알림 1건 저장. `notification.id == null` ⇒ INSERT 후 id 가 채워진 값을 반환. */
    fun save(notification: Notification): Notification

    /**
     * [userId] 가 받은 알림을 전부 물리 삭제. **회원탈퇴 전용** (mypage T-B4).
     *
     * ## 🔴 왜 이것만 보존이 아닌가
     *
     * 탈퇴의 기본 방침은 *"익명화 + 기록 보존"* 이다 — 챌린지·계약서·서명·결과는 **상대의
     * 기록이기도** 해서 지우면 탈퇴하지 않은 사람의 과거가 무너진다. 그런데 수신함은 다르다.
     * `notifications` 는 **본인만 읽는 개인 데이터**라 양자 기록이 아니고, *"누가 나에게 무엇을
     * 했는지"* 를 담고 있어 **개인정보 삭제 축**에 속한다 (pm-lead 판정). 아무도 읽을 수 없는
     * 채로 남겨 둘 이유가 없다.
     *
     * @return 삭제된 row 수. 호출부는 로그에만 쓴다 — 0 건도 정상이다.
     */
    fun deleteAllByUserId(userId: Long): Int
}
