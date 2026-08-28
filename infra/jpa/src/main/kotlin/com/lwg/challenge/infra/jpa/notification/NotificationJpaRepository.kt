package com.lwg.challenge.infra.jpa.notification

import com.lwg.challenge.infra.entity.notification.NotificationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * notifications 테이블 CRUD. 모듈 외부에 노출되지 않는다 (NotificationRepositoryImpl 만 사용).
 *
 * 조회 메서드가 없는 것은 의도다 — 알림 목록 화면이 이번 범위 밖이라 **쌓기만 한다.**
 * `idx_notifications_user_read_created` 인덱스는 V1 부터 있으므로 화면이 붙을 때 바로 쓸 수 있다.
 * (아래 삭제는 조회가 아니라 탈퇴 정리다.)
 */
interface NotificationJpaRepository : JpaRepository<NotificationEntity, Long> {

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
