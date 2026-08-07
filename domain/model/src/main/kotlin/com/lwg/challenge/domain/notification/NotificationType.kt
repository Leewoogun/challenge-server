package com.lwg.challenge.domain.notification

/**
 * 알림 종류. `notifications.type` (`VARCHAR(30)`) 에 **enum name 그대로** 저장된다.
 *
 * push-fcm 계약 §0.6 에서 V1__init.sql 주석의 7종을 재정의한 결과다. 실제 정정은
 * `V8__notification_type_recomment.sql` 이 `COMMENT ON COLUMN` 으로 수행한다 (DML 0건).
 *
 * ## `SIGN_REQUEST` 가 없는 이유
 *
 * `soul-oath` 에서 **수락과 서명을 원자 요청으로 묶었다.** 그래서 *"수락은 했고 서명은 안 한"*
 * 상태가 존재하지 않는다. 부를 대상이 없는 알림이라 타입 자체를 폐기했다.
 * ⚠️ 되살리려면 먼저 그 상태를 다시 만들어야 한다 — 이름만 추가하면 영원히 발송되지 않는다.
 *
 * ## 왜 미구현 타입까지 지금 선언하는가
 *
 * 이 enum 은 **어휘(vocabulary)** 이지 구현 목록이 아니다. DB 컬럼 주석이 전체 어휘를 나열하므로
 * 둘이 어긋나면 주석이 다시 낡는다 — 정확히 이번에 고친 문제다. [NotificationTypeTest] 가
 * 폐기된 이름 부재 + V8 주석과의 목록 일치를 고정한다.
 *
 * 참고로 컬럼은 `VARCHAR(30)` 이고 가장 긴 이름이 `CHALLENGE_ACCEPTED`(18자) 라 여유가 있다.
 *
 * | 타입 | 수신자 | 트리거 | 상태 |
 * |---|---|---|---|
 * | [CHALLENGE_REQUEST] | 상대 | `POST /challenges` | push-fcm 범위 |
 * | [CHALLENGE_ACCEPTED] | 신청자 | `POST /challenges/{id}/accept` | push-fcm 범위 (신규) |
 * | [CHALLENGE_REJECTED] | 신청자 | `POST /challenges/{id}/reject` | push-fcm 범위 (신규) |
 * | [REMIND] | 양측 | 마감 1시간 전 배치 | 인증 feature 대기 |
 * | [OPPONENT_VERIFIED] | 상대 | 인증 제출 | 인증 feature 대기 |
 * | [RESULT] | 양측 | 자정 판정 배치 | 판정 feature 대기 |
 * | [TAUNT] | 상대 | 도발 전송 | 도발 feature 대기 |
 * | [FRIEND_REQUEST] | 수신자 | 친구 요청 | 후속 |
 */
enum class NotificationType {

    /** 챌린지 신청이 도착했다. 수신자 = 상대. */
    CHALLENGE_REQUEST,

    /** 상대가 수락(=서명)했다. 수신자 = 신청자. */
    CHALLENGE_ACCEPTED,

    /** 상대가 거절했다. 수신자 = 신청자. */
    CHALLENGE_REJECTED,

    /** 마감이 임박했다. 수신자 = 양측. 인증 feature 에서 발송이 붙는다. */
    REMIND,

    /** 상대가 인증을 제출했다. 인증 feature 에서 발송이 붙는다. */
    OPPONENT_VERIFIED,

    /** 승패가 판정됐다. 수신자 = 양측. 판정 배치 feature 에서 발송이 붙는다. */
    RESULT,

    /** 도발 메시지가 도착했다. 도발 feature 에서 발송이 붙는다. */
    TAUNT,

    /** 친구 요청이 도착했다. `reference_id` 가 challenge 가 아닌 유일한 타입이 될 예정이다. */
    FRIEND_REQUEST,
}
