package com.lwg.challenge.service.notification

import com.lwg.challenge.domain.notification.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 알림 **상태 변경** — `POST /api/v1/notifications/read-all` 의 단일 write 트랜잭션.
 *
 * ## 🔵 왜 `NotificationQueryService` 와 나눴나
 *
 * 이 레포의 선례를 따른 것이다 — `ChallengeCommandService` 가 상태 전이를 맡고
 * `ActiveChallengeService` / `ChallengeHistoryService` 가 조회를 맡는 것과 같은 분리다.
 * 조회 서비스는 전부 `@Transactional(readOnly = true)` 라, 쓰기 메서드를 하나라도 섞으면
 * 클래스 기본값과 메서드 어노테이션이 엇갈리기 시작한다.
 *
 * ## 🔴 *"목록을 열면 전부 읽음"* — 행 단위 읽음은 채택하지 않았다
 *
 * 알림마다 `isRead` 를 따로 내리는 방식은 **"언제 읽음인가" 라는 제품 결정을 영구히 끌고
 * 다닌다** (화면에 보이면? 탭하면? 몇 초 이상 노출되면?). 목록을 여는 순간 전부 읽음으로
 * 고정하면 그 결정 자체가 사라지고, **엔드포인트 2개로 홈 벨 뱃지가 성립**한다. 그래서
 * `NotificationDto` 에는 지금도 `isRead` 가 없다 — 그 부재는 의도이고, 여기서도 되살리지 않는다.
 */
@Service
class NotificationCommandService(
    private val notificationRepository: NotificationRepository,
) {

    /**
     * [userId] 의 안 읽은 알림을 전부 읽음으로 바꾸고, **갱신 후의 안 읽은 개수**를 반환한다.
     *
     * ## 🔴 `0` 을 하드코딩해서 반환하지 마라
     *
     * 이 메서드가 `return 0` 이었다면 응답은 **UPDATE 가 한 행도 건드리지 못했을 때에도 0** 이다.
     * 조건이 잘못 걸렸든, 트랜잭션이 다른 커넥션을 봤든, 쿼리가 조용히 실패했든 — 앱은 0 을 받고
     * **뱃지를 끈다.** 그리고 다음 홈 진입에서 `unread-count` 가 다시 3 을 주면 뱃지가 되살아나는데,
     * 그건 *"읽음 처리가 안 먹었다"* 가 아니라 *"알림이 새로 왔다"* 처럼 보인다. 서버 로그에도
     * 아무것도 남지 않는다.
     *
     * 그래서 **같은 트랜잭션 안에서 실제로 다시 센다.** 반환값이 0 이라는 사실 자체가 갱신이
     * 일어났다는 증거가 되고, 0 이 아닌 값이 나오면 그 자리에서 드러난다.
     *
     * ⚠️ [NotificationRepository.markAllAsReadByUserId] 는 벌크 UPDATE 라 영속 컨텍스트를
     * 거치지 않지만, 뒤이은 COUNT 는 **DB 를 직접 집계**하므로 갱신 결과를 본다.
     *
     * **멱등하다** — 이미 다 읽은 상태에서 다시 불러도 갱신 대상이 없어 그대로 0 이다.
     *
     * @return 처리 후의 안 읽은 개수. 정상 경로에서는 **0**.
     */
    @Transactional
    fun markAllAsRead(userId: Long): Int {
        notificationRepository.markAllAsReadByUserId(userId)
        // 🔴 하드코딩 0 이 아니라 실측이다 — 갱신이 실제로 일어났는지를 응답이 증명한다.
        return notificationRepository.countUnreadByUserId(userId).toInt()
    }
}
