package com.lwg.challenge.domain.contract

import java.time.LocalDateTime

/**
 * 영혼의 맹세(계약서) 도메인 모델. `contracts` 테이블과 1:1 (soul-oath).
 *
 * `challenge_id` 에 UNIQUE 가 걸려 있어 **챌린지당 계약서는 최대 1건**이다.
 *
 * ## 생명주기
 * ```
 * 챌린지 생성  → row 생성, challengerSignature 만 채워짐, isFinalized=false
 * 상대 수락+서명 → opponentSignature 채워짐, isFinalized=true → 챌린지가 IN_PROGRESS 로
 * ```
 *
 * **`isFinalized` 는 contract 의 속성이지 challenge 의 상태가 아니다.** 그래서
 * `ChallengeStatus.CONTRACT_SIGNING` 을 도입하지 않았다 (api-contract §0.3).
 *
 * 서명 데이터는 벡터 스트로크 JSON 문자열이다. 서버는 **파싱해 렌더하지 않고** 구조만 검증한다.
 * 형식은 [SignatureValidator] KDoc 과 `contracts.*_signature_data` 컬럼 주석 참조.
 *
 * 시간 필드는 KST (ADR-0010).
 */
data class Contract(
    val id: Long?,
    val challengeId: Long,
    val content: String,
    /** 벡터 스트로크 JSON. null = 미서명. */
    val challengerSignatureData: String?,
    val challengerSignedAt: LocalDateTime?,
    /** 벡터 스트로크 JSON. null = 미서명. */
    val opponentSignatureData: String?,
    val opponentSignedAt: LocalDateTime?,
    /** 양측 서명 완료. true 가 된 시점에만 챌린지가 IN_PROGRESS 로 전환된다. */
    val isFinalized: Boolean,
    val createdAt: LocalDateTime,
)
