package com.lwg.challenge.domain.verification

import java.time.LocalDateTime

/**
 * 챌린지 인증(미션 수행 사진) 도메인 모델.
 *
 * 1 (challengeId, userId) → 1 row (UNIQUE 제약 — V1__init.sql).
 *
 * V1 에선 `photo_url` / `verified_at` 이 NOT NULL 이었으나, "아직 인증 안 함" 상태(PENDING)를 표현할 수 없어
 * V4 마이그레이션에서 nullable 화하고 `status` 컬럼을 추가했다.
 *
 * row 자체가 없으면 PENDING 으로 간주한다 (HomeService 가 LEFT 매핑).
 *
 * ## AI 판별 로그 4필드 (ai-verification, V13)
 *
 * [aiVerdict] / [aiConfidence] / [aiReason] / [aiCheckedAt] 는 **전부 nullable 이고 기본값이
 * null 이다.** 기본값을 준 것은 의도다 — 기존 생성부(수락 시 PENDING row 2건, 테스트 시드)가
 * 이 4개를 모르고도 컴파일돼야 한다. **판정하지 않은 것이 정상 상태**이기 때문이다.
 *
 * ⚠️ 다른 nullable 필드(`photoUrl`/`verifiedAt`)에 기본값이 없는 것과 대비된다. 그쪽은
 * 매핑을 빠뜨리면 사진이 조용히 사라지는 필수 값이고, 이쪽은 **없는 것이 다수인 부가 로그**다.
 */
data class Verification(
    val id: Long?,
    val challengeId: Long,
    val userId: Long,
    val status: VerificationStatus,
    val photoUrl: String?,
    val verifiedAt: LocalDateTime?,
    val createdAt: LocalDateTime,

    /** AI 판정 결과. null = 아직 검증하지 않음 (미제출 · 기권 · fail-open 포함). */
    val aiVerdict: VerdictOutcome? = null,

    /** 0.00~1.00. [MissionVerdict.REJECT_THRESHOLD] 와 비교되는 값. */
    val aiConfidence: Double? = null,

    /** 🔴 사용자 화면에 그대로 노출되는 한 줄 사유. */
    val aiReason: String? = null,

    /**
     * 판정 시각 (KST 벽시계, ADR-0010).
     *
     * 🔴 **non-null 이면 다시 호출하지 않는다.** 배치 멱등의 근거이자 *"제출된 인증 1건당
     * 정확히 1회"* (ADR-0013 §결정 3)를 보장하는 유일한 수단이다.
     *
     * ⚠️ **호출했지만 실패한 건에도 이 값이 찍힌다.** 안 찍으면 Gemini 가 죽어 있는 동안
     * 배치를 돌릴 때마다 같은 건을 끝없이 다시 부른다 — 잔액이 마른 상황에서 그건 비용이
     * 아니라 지연이 된다. `ai_verdict = ERROR` 가 *"불렀고 실패했다"* 를 구분해 준다.
     */
    val aiCheckedAt: LocalDateTime? = null,
) {

    /**
     * 이 인증이 **AI 판별로 미인증 처리된 것**인가 (ai-verification).
     *
     * ## 🔴 wire 게이트의 유일한 판정자다 — 계약 조항이다
     *
     * `ai_reason` **컬럼**은 rubric 튜닝 근거라 판정 종류를 안 가리고 **전건 저장**된다
     * (spec T-B2, V13 주석). 하지만 **wire 는 사용자 화면**이라 미인증 확정 건만 실어야 한다.
     * 게이트가 없으면 이런 것들이 *"인증이 인정되지 않았어요: …"* 로 사용자에게 나간다:
     *
     * | [aiVerdict] | 게이트 없으면 새는 것 |
     * |---|---|
     * | `PASS` | **이겼는데** 통과 사유가 반려 문구로 붙는다 |
     * | `UNVERIFIABLE` | *"AI 검증이 비활성 상태입니다 (API 키 미설정)"* — **무키 상태의 제출 전건** |
     * | `ERROR` | 🔴 **네트워크 예외 메시지가 그대로** 사용자에게 |
     * | `UNCERTAIN` | 통과인데 부정적 문구 |
     *
     * ## 🔴 조건식을 여기 새로 적지 않았다 — [MissionVerdict.rejectsVerification] 에 위임한다
     *
     * 저장된 컬럼으로 판정을 **재구성해서** 그 함수에 묻는다. 여기에 직접
     * `outcome == REJECT && confidence >= 0.80` 을 적으면 **판정 규칙의 사본**이 생기고,
     * 그건 [MissionVerdict] KDoc 이 *"호출부마다 조합하면 한 곳이 빠지는 날 조용히 정상
     * 수행자가 패배한다"* 로 경계한 바로 그 형태다. 문턱이 바뀌는 날 이쪽이 따라오지 않으면
     * **화면과 판정이 갈린다.**
     *
     * ⚠️ 판별하지 않은 건([aiVerdict] 또는 [aiConfidence] 가 null)은 **false** 다 —
     * 미제출 · 기권 · fail-open 이 전부 여기에 속한다.
     */
    fun rejectedByAi(): Boolean {
        val outcome = aiVerdict ?: return false
        val confidence = aiConfidence ?: return false
        return MissionVerdict(outcome, confidence, aiReason.orEmpty()).rejectsVerification()
    }
}

/**
 * 모바일과 정확히 일치하는 세 값.
 * - PENDING : 아직 인증 안 함 (또는 row 자체 부재)
 * - VERIFIED: 미션 인증 완료
 * - FAILED  : 시간 내 인증 실패 / 거부 등
 *
 * 🔴 **`FAILED` 의 의미가 ai-verification 에서 둘로 늘었다**: ⓐ 마감까지 미제출
 * (`judge()` 가 `PENDING` 을 전이) ⓑ **제출했으나 AI 가 미션 증거로 인정하지 않음**
 * (검증 단계가 `VERIFIED` 를 전이). 판정은 둘을 **같게** 대하고(미인증), 표시는
 * [Verification.rejectedByAi] 로 갈린다.
 *
 * ⚠️ ⓑ 때문에 **`FAILED` 인데 사진이 있는 row** 가 처음 생겼다 — *"`FAILED` 면 사진이 없다"* 를
 * 전제하는 코드를 새로 쓰지 마라.
 */
enum class VerificationStatus {
    PENDING,
    VERIFIED,
    FAILED,
}
