package com.lwg.challenge.domain.challenge

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 받은 도전장 1건 — `GET /api/v1/challenges/received` 전용 projection.
 *
 * `challenges` ↔ `users` (challenger 방향) JOIN 결과를 한 번에 담는다. 챌린저 정보를 별도 조회하지
 * 않기 위한 read model 이라 [Challenge] 와 달리 상대(=나) 쪽 필드가 없다.
 *
 * `opponentMission` 이 없는 이유: 이 목록은 `PENDING` 만 담는데, PENDING 구간에서는 정의상 항상 null 이다
 * (상대가 수락하며 입력한다 — challenge-create spec 결정 2).
 *
 * 시간 필드는 DB `TIMESTAMP` (tz 없음) 그대로. 프로젝트 규약상 **KST 로 간주**하며 (ADR-0010)
 * 컨트롤러가 변환 없이 `"yyyy-MM-dd HH:mm:ss"` 로 직렬화한다.
 */
data class ReceivedChallenge(
    val challengeId: Long,
    val challengerId: Long,
    val challengerNickname: String,
    /** 카카오 프로필 미설정 시 null. api-contract 에서 유일하게 nullable 로 명시된 필드. */
    val challengerProfileImageUrl: String?,
    val challengerMission: String,
    val betContent: String,
    val challengeDate: LocalDate,
    val deadline: LocalDateTime,
    val createdAt: LocalDateTime,
)
