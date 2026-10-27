package com.lwg.challenge.infra.jpa.notification

import com.lwg.challenge.infra.entity.notification.NotificationEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * notifications 테이블 CRUD. 모듈 외부에 노출되지 않는다 (NotificationRepositoryImpl 만 사용).
 *
 * 알림 목록 화면이 붙으면서 조회 2개가 생겼다 ([findFirstPageByUserId] / [findPageByUserIdFrom]).
 * (맨 아래 삭제는 조회가 아니라 탈퇴 정리다.)
 *
 * 홈 벨 뱃지가 붙으면서 읽음 축 2개가 더 생겼다 ([countUnreadByUserId] / [markAllAsReadByUserId]).
 *
 * ⚠️ 다섯 쿼리 모두 `n.user.id` 가 아니라 **`n.userId`** 로 쓴다 — `userId` 는 `@ManyToOne` 연관이
 * 아니라 **plain `Long` 컬럼**이다 (`NotificationEntity` 참조). 아래 [deleteAllByUserId] 와 같은
 * 사정이고, 연관인 줄 알고 `n.user.id` 로 쓰면 JPQL 파싱 단계에서 깨진다.
 *
 * ## 🔴 정렬·커서 축이 `created_at` 이 아니라 `id` 인 이유
 *
 * `notifications.created_at` 은 **DB DEFAULT 가 아니다** — `NotificationEntity` 가
 * `KstTime.now()` 로 값을 채워 INSERT 에 실어 보낸다. 그래서 애플리케이션 시계가 NTP 보정으로
 * **뒤로 밀리면** 나중에 삽입된 행이 더 이른 `created_at` 을 갖는 구간이 생긴다. 커서가 그 위에
 * 서면 `created_at < (직전 페이지 마지막 값)` 판정이 어긋난 구간의 행을 통째로 건너뛰는데, 🔴 **예외도 로그도
 * 남지 않는다** — 사용자에게는 "알림이 그냥 안 왔다" 로만 보이고 서버는 정상으로 보고한다.
 *
 * `id` 는 `BIGSERIAL` 이라 시퀀스가 발급하므로 **삽입 순서와 정확히 일치**하고 **동률이 없다.**
 * 동률이 없으니 `(created_at, id)` 같은 복합 커서도 필요 없고, 커서가 `Long` 하나로 끝난다.
 *
 * ## 인덱스 — 목록 쿼리용으로는 지금도 추가하지 않는다
 *
 * V1 의 `idx_notifications_user_read_created(user_id, is_read, created_at DESC)` 는 **안 읽은
 * 개수 집계용**으로 만든 것이다. 위 목록 쿼리 2개에는 선두 컬럼(`user_id`) 만 쓰이고 `id` 정렬은
 * 인덱스 밖에서 따로 일어난다. 🔴 **그래도 목록용 인덱스를 지금 만들지 않는다** — 한 사용자의
 * 알림 행 수는 작고, 느려진 것이 **관측된 뒤에** `(user_id, id DESC)` 를 추가하면 된다.
 *
 * 🔵 **반면 [countUnreadByUserId] 는 그 인덱스가 애초에 겨냥한 쿼리다** — 선두 두 컬럼
 * `(user_id, is_read)` 가 WHERE 절과 정확히 맞는다. V1 에서 만들어 두고 아무도 쓰지 않던 인덱스가
 * 여기서 처음 제 용도로 쓰인다. (목록 쿼리가 이 인덱스를 정렬에 **못** 쓰는 것과 대비되는
 * 지점이니 위 문단과 함께 읽어라.)
 */
interface NotificationJpaRepository : JpaRepository<NotificationEntity, Long> {

    /**
     * `nextNotificationId` 없는 **첫 페이지** — [userId] 의 최신 알림부터 `id` 내림차순.
     *
     * ## 🔴 `(:nextNotificationId IS NULL OR n.id <= :nextNotificationId)` 단일 쿼리를 쓰지 않았다
     *
     * 그 형태는 바인딩되는 `nextNotificationId` 가 null 일 때 **파라미터의 타입을 Hibernate 가 추론할 근거가
     * 없어** 드라이버/방언 조합에 따라 판단이 갈리는 지점이다 (Postgres 는 타입 없는 파라미터
     * 비교를 거부할 수 있다). 분기를 **메서드 2개**로 올려 두면 각 쿼리의 파라미터가 항상
     * non-null 이라 그 문제 자체가 생기지 않는다. 분기는 `NotificationRepositoryImpl` 이 한다.
     *
     * @param pageable **LIMIT 전용**이다 — 정렬은 JPQL 의 `ORDER BY` 가 이미 결정했으므로
     *   `PageRequest.of(0, limit)` 처럼 정렬 없는 값을 넘긴다 (Sort 를 실으면 두 곳이 경쟁한다).
     * @return 반환 타입이 `Page` 가 아니라 **`List` 라 COUNT 쿼리가 나가지 않는다.** 커서
     *   페이지네이션은 전체 건수를 쓰지 않으므로 COUNT 는 순수한 낭비다.
     */
    @Query(
        """
        SELECT n FROM NotificationEntity n
         WHERE n.userId = :userId
         ORDER BY n.id DESC
        """,
    )
    fun findFirstPageByUserId(@Param("userId") userId: Long, pageable: Pageable): List<NotificationEntity>

    /**
     * 다음 페이지 — [nextNotificationId] **이하**의 `id` 를 내림차순으로.
     *
     * 🔴 **포함 경계(`<=`)다** (ADR-0014 §3). [nextNotificationId] 는 직전 응답이 `size + 1` 조회의
     * 초과분으로 준 **다음 페이지 첫 항목의 id** 라, 그 행 자신이 이 페이지의 첫 행이어야 한다.
     * `<` 로 쓰면 페이지 경계마다 **1건씩 조용히 누락된다.** 그 사이 해당 행이 삭제됐어도 `<=` 라
     * 그 이하가 그대로 이어진다.
     * [findFirstPageByUserId] 와 나눈 이유·`pageable`·COUNT 는 그쪽 KDoc 과 같다.
     */
    @Query(
        """
        SELECT n FROM NotificationEntity n
         WHERE n.userId = :userId
           AND n.id <= :nextNotificationId
         ORDER BY n.id DESC
        """,
    )
    fun findPageByUserIdFrom(
        @Param("userId") userId: Long,
        @Param("nextNotificationId") nextNotificationId: Long,
        pageable: Pageable,
    ): List<NotificationEntity>

    /**
     * [userId] 의 **안 읽은** 알림 개수 — 홈 벨 뱃지가 보는 값.
     *
     * 🔵 **V1 의 `idx_notifications_user_read_created(user_id, is_read, created_at DESC)` 가 드디어
     * 제 용도로 쓰이는 자리다.** 그 인덱스는 애초에 *안 읽은 개수 집계*를 위해 만들어진 것이고,
     * 이 WHERE 절이 선두 두 컬럼 `(user_id, is_read)` 와 정확히 맞는다. 위 목록 쿼리들이 같은
     * 인덱스를 **정렬에는 못 쓴다**고 적어 둔 것과 대비되는 지점이다 (클래스 KDoc 참조).
     */
    @Query("SELECT COUNT(n) FROM NotificationEntity n WHERE n.userId = :userId AND n.isRead = false")
    fun countUnreadByUserId(@Param("userId") userId: Long): Long

    /**
     * [userId] 의 **안 읽은** 알림을 전부 읽음으로 바꾼다 — *"목록을 열면 전부 읽음"* 정책의
     * 실행 지점. 행 단위 읽음을 채택하지 않은 근거는 `NotificationRepository.markAllAsReadByUserId`
     * KDoc 에 있다.
     *
     * ## 🔵 `AND n.isRead = false` 가 [countUnreadByUserId] 와 **양쪽에** 있다
     *
     * COUNT 쪽에서는 그게 곧 세는 대상의 정의지만, UPDATE 쪽에서는 **이미 읽은 행을 다시 쓰지
     * 않게** 하는 조건이다. 덕분에 (a) 두 번째 호출부터 갱신 row 수가 **0** 이 되어 **멱등성이
     * 반환값으로 눈에 보이고**, (b) 이미 읽은 수백 행을 같은 값으로 덮어쓰는 낭비 write 가
     * 사라진다. 조건 없이 `SET n.isRead = true` 로 쓰면 둘 다 잃는다 — 매번 전체 행을 다시 쓰고,
     * 반환값은 *"뭔가 바뀌었다"* 와 *"원래 그랬다"* 를 구분하지 못한다.
     *
     * ⚠️ 벌크 UPDATE 라 **영속 컨텍스트를 거치지 않는다** ([deleteAllByUserId] 와 같은 사정) —
     * 1차 캐시에 이미 올라온 인스턴스가 있으면 그 값은 낡은 채로 남는다. 다만 같은 트랜잭션에서
     * 뒤이어 도는 [countUnreadByUserId] 는 **DB 를 직접 집계**하므로 이 갱신 결과를 본다
     * (`NotificationCommandService.markAllAsRead` 가 그것에 기대고 있다).
     *
     * @return 실제로 갱신된 row 수. 이미 다 읽었으면 **0** 이고 그건 정상이다.
     */
    @Modifying
    @Query("UPDATE NotificationEntity n SET n.isRead = true WHERE n.userId = :userId AND n.isRead = false")
    fun markAllAsReadByUserId(@Param("userId") userId: Long): Int

    /**
     * 한 사용자의 수신함을 통째로 물리 삭제 (mypage T-B4 — 회원탈퇴).
     *
     * `userId` 는 `@ManyToOne` 연관이 아니라 **plain `Long` 컬럼**이라 (`NotificationEntity`
     * 참조) `n.user.id` 가 아니라 `n.userId` 로 쓴다. `FriendshipJpaRepository.deleteAllByUserId`
     * 와 같은 사정이다.
     *
     * 수신함만 개인정보 삭제 축에 속하는 근거는 `NotificationRepository.deleteAllByUserId` KDoc.
     *
     * ⚠️ 벌크 DELETE 라 영속 컨텍스트를 거치지 않는다. 탈퇴 경로는 삭제 후 이 테이블을 다시
     * 읽지 않으므로 1차 캐시의 낡은 인스턴스를 볼 일이 지금은 없다.
     */
    @Modifying
    @Query(
        """
        DELETE FROM NotificationEntity n
         WHERE n.userId = :userId
        """,
    )
    fun deleteAllByUserId(@Param("userId") userId: Long): Int
}
