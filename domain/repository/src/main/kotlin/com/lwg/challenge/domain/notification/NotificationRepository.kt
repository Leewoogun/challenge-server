package com.lwg.challenge.domain.notification

/**
 * 알림 영속성 추상. 구현체는 `:infra:repositoryimpl` 의 `NotificationRepositoryImpl`.
 *
 * **알림 목록 화면이 붙으면서 조회가 생겼다** — [findPageByUserId]. 그전까지는 저장만 하고
 * 아무도 읽지 않는 테이블이었고, 이 KDoc 도 *"조회는 아직 없다"* 라고 적고 있었다.
 * ([deleteAllByUserId] 는 그 조회가 아니라 **탈퇴 정리**다 — 화면과 무관하다.)
 *
 * **그다음 홈 벨 뱃지가 붙으면서 읽음 축이 생겼다** — [countUnreadByUserId] / [markAllAsReadByUserId].
 * `is_read` 컬럼은 V1 부터 있었지만 아무도 쓰지 않던 값이었고, 이 둘이 처음으로 그것을 읽고 쓴다.
 */
interface NotificationRepository {

    /** 알림 1건 저장. `notification.id == null` ⇒ INSERT 후 id 가 채워진 값을 반환. */
    fun save(notification: Notification): Notification

    /**
     * [userId] 의 알림을 **`id` 내림차순(= 최신순)** 으로 [limit] 건까지 읽는다 — 커서 페이지네이션.
     *
     * 정렬 키가 `createdAt` 이 아니라 `id` 인 근거는 `NotificationJpaRepository` KDoc 에 있다
     * (요약: `createdAt` 은 DB DEFAULT 가 아니라 애플리케이션이 채워 보내는 값이라 삽입 순서와
     * 어긋날 수 있고, 커서가 그 위에 서면 행이 조용히 건너뛰어진다).
     *
     * @param cursor 이 값 **미만**의 `id` 만 반환한다. **null 이면 첫 페이지**다 (커서 없이 최신부터).
     * @param limit 읽어 올 최대 건수. 🔴 호출부는 화면이 요청한 `size` 가 아니라 **`size + 1`** 을
     *   넣는다 — 그 초과분 1건이 존재하는지가 곧 *"다음 페이지가 있나"* 의 답이고, 그래야 COUNT
     *   쿼리 없이 판정된다 (`NotificationQueryService.getNotifications`).
     * @return `id` 내림차순 목록. 해당 구간에 알림이 없으면 **빈 리스트**다 (null 아님).
     */
    fun findPageByUserId(userId: Long, cursor: Long?, limit: Int): List<Notification>

    /**
     * [userId] 의 **안 읽은** 알림 개수 — 홈 벨 뱃지가 보는 값.
     *
     * `GET /notifications/unread-count` 와 `POST /notifications/read-all` 이 **둘 다 이 값**을
     * 응답에 싣는다. 앱이 뱃지를 켜는 근거를 한 곳으로 모으기 위한 것이고, 근거는
     * `UnreadCountData` KDoc 에 있다.
     *
     * @return 0 이상. 안 읽은 것이 없으면 **0** 이고 그건 정상이다 (에러가 아니다).
     */
    fun countUnreadByUserId(userId: Long): Long

    /**
     * [userId] 의 **안 읽은** 알림을 전부 읽음으로 바꾼다.
     *
     * ## 🔴 행 단위 읽음이 아니라 *"목록을 열면 전부 읽음"* 이다
     *
     * 알림마다 `isRead` 를 따로 내리는 방식은 **"언제 읽음인가" 라는 제품 결정을 영구히 끌고
     * 다닌다** — 화면에 보이면? 탭하면? 몇 초 이상 노출되면? 스크롤로 지나가면? 목록을 여는
     * 순간 전부 읽음으로 고정하면 그 결정 자체가 사라지고, **엔드포인트 2개로 홈 벨 뱃지가
     * 성립**한다. 그래서 행 단위 읽음은 채택하지 않았다.
     *
     * **멱등하다** — 이미 다 읽은 상태에서 다시 불러도 갱신할 행이 없어 0 을 반환한다.
     *
     * @return 실제로 갱신된 row 수. 이미 읽은 행은 애초에 대상이 아니므로 **0 건도 정상**이다.
     */
    fun markAllAsReadByUserId(userId: Long): Int

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
