package com.lwg.challenge.domain.notification

import java.time.LocalDateTime

/**
 * 알림 도메인 모델. `notifications` 테이블과 1:1 (V1 스키마 + V8 주석 정정).
 *
 * **row 저장과 푸시 발송은 별개다.** 수신자에게 `fcm_token` 이 없어도(알림 권한 거부·로그아웃)
 * 이 row 는 저장된다 — 알림 목록 화면이 붙을 때 과거 알림이 있어야 하기 때문이다 (계약 §3).
 *
 * `referenceId` 는 타입에 따라 참조 대상이 다르다. 현재 `CHALLENGE_*` 3종은 전부 `challenges.id` 이고
 * FK 제약을 걸지 않는 이유가 그것이다 (V8 컬럼 주석 참조).
 *
 * `createdAt` 은 KST 벽시계 (ADR-0010).
 */
data class Notification(
    val id: Long?,
    val userId: Long,
    val type: NotificationType,
    val title: String,
    val body: String,
    val referenceId: Long?,
    val isRead: Boolean,
    val createdAt: LocalDateTime,
)
