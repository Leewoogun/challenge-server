package com.lwg.challenge.service.notification

import com.lwg.challenge.domain.notification.NotificationType

/**
 * 챌린지 상태 변화로 알림이 필요해졌다는 사실 (push-fcm T-B5).
 *
 * `ChallengeCommandService` 가 트랜잭션 **안**에서 발행하고, [NotificationDispatcher] 가
 * 커밋 **후**에 받는다.
 *
 * ## 🔴 닉네임이 아니라 id 만 싣는다
 *
 * 문구에 필요한 건 상대 닉네임인데 **여기서는 id 만 넘긴다.** 발행 지점이 트랜잭션 안(사용자가
 * 응답을 기다리는 구간)이라, 알림 문구를 만들자고 거기서 추가 조회를 하면 **알림 때문에 챌린지
 * 요청이 느려진다.** 닉네임 조회는 커밋 후에 리스너가 한다 — 그쪽은 아무도 기다리지 않는다.
 *
 * 부수 효과로 `ChallengeCommandService` 의 변경이 "이벤트 발행 한 줄" 로 끝난다. 그 서비스는
 * 중복 검사·계약서 렌더링·verifications 생성이 얽힌 민감한 코드라 손을 덜 댈수록 좋다.
 *
 * @param recipientId 알림을 **받을** 사람. 신청이면 상대, 수락·거절이면 신청자다.
 * @param actorId 알림을 **유발한** 사람. 문구의 `{상대닉네임}` 자리에 들어간다.
 * @param challengeId `notifications.reference_id` 이자 `data.challengeId` 로 실린다.
 */
data class ChallengeNotificationEvent(
    val recipientId: Long,
    val actorId: Long,
    val type: NotificationType,
    val challengeId: Long,
)
