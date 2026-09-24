package com.lwg.challenge.domain.verification

/**
 * AI 인증 판별 결과 — **사진 1장에 대한 pass/fail** (ADR-0013 §결정 2).
 *
 * ## 🔴 이 타입은 승패를 말하지 않는다
 *
 * 답하는 것은 *"이 사진이 이 미션을 수행한 증거인가"* 하나뿐이고, *"누가 이겼는가"* 는
 * [com.lwg.challenge.domain.challenge.ChallengeVerdict] 의 몫이다. 둘을 섞으면 승패 규칙의
 * 사본이 프롬프트 안에 생기고, 그러면 `ChallengeVerdict` 가 바뀌는 날 **아무것도 실패하지
 * 않은 채로 틀린 승패가 나간다** (ADR-0013 §근거 2).
 *
 * 이 판정이 하는 일은 `ChallengeVerdict.decide` 의 **입력**인 *"인증했다"* 를 뒤집는 것뿐이다.
 *
 * ## 🔴 미인증 처리는 [rejectsVerification] 한 곳에서만 판단한다
 *
 * 조건이 여럿(판정 가능한 미션 + REJECT + 신뢰도)이라 호출부마다 조합하면 한 곳이 빠지는 날
 * **조용히 정상 수행자가 패배한다.** 되돌릴 경로가 없는 판정이므로 판단을 도메인에 두고
 * 호출부는 묻기만 한다.
 */
data class MissionVerdict(
    val outcome: VerdictOutcome,
    val confidence: Double,
    val reason: String,
) {

    /**
     * 이 판정이 *"인증 안 한 것으로 처리"* 를 정당화하는가.
     *
     * 🔴 **기본값은 통과다.** [VerdictOutcome.REJECT] 이면서 신뢰도가 [REJECT_THRESHOLD] 이상일
     * 때만 true 이고, 그 외 전부(판정 불가능한 미션, 불확실, 저신뢰, 호출 실패)는 사용자 편이다.
     *
     * 근거는 비대칭이다 (ADR-0013 §근거 5):
     * - 부정행위를 놓침 → 이길 자격 없는 사람이 이긴다. **다음 챌린지가 있다**
     * - 정상 수행자를 반려 → **실제로 미션을 해낸 사람이 진다. 회복 경로 0**
     *
     * 후자가 훨씬 비싸므로 문턱을 느슨한 쪽으로 잡는다.
     */
    fun rejectsVerification(): Boolean =
        outcome == VerdictOutcome.REJECT && confidence >= REJECT_THRESHOLD

    companion object {
        /**
         * 반려 문턱. **올리면 더 느슨해지고(오탐 감소), 내리면 더 빡빡해진다.**
         *
         * 운영 중 부정행위가 빈발하면 이 값을 만지기 전에 `ai_reason`/`ai_confidence` 로그를
         * 먼저 보라 — 그 로그가 이 상수를 근거 있게 움직일 유일한 재료다 (V13 마이그레이션 주석).
         */
        const val REJECT_THRESHOLD = 0.80

        /** 호출 실패 · 타임아웃 · 스키마 파싱 실패용. fail-open 의 마지막 겹이다. */
        fun error(reason: String) = MissionVerdict(VerdictOutcome.ERROR, 0.0, reason)

        /** 사진으로 판정할 수 없는 종류의 미션. */
        fun unverifiable(reason: String) = MissionVerdict(VerdictOutcome.UNVERIFIABLE, 0.0, reason)
    }
}

/**
 * - `PASS`         : 미션 수행 증거로 타당하다
 * - `REJECT`       : 미션과 무관하다
 * - `UNCERTAIN`    : 판단이 서지 않는다
 * - `UNVERIFIABLE` : 사진으로 검증할 수 없는 **종류**의 미션 (*"일찍 자기"*, *"짜증 안 내기"*)
 * - `ERROR`        : 호출 실패 · 타임아웃 · 스키마 파싱 실패
 *
 * 🔑 **`UNVERIFIABLE` 이 오탐 방어의 핵심이다.** 미션이 자유 텍스트라 사진으로 검증 불가능한
 * 미션이 섞여 들어온다. AI 에게 *"판정하라"* 가 아니라 **"판정할 수 있는 종류인지 먼저
 * 판단하라"** 를 시키면 오탐의 가장 큰 원인이 앞에서 잘린다 (ADR-0013 §결정 2).
 *
 * ⚠️ **이 이름들은 `verifications.ai_verdict` 에 문자열 그대로 저장된다** (V13). 값을 바꾸면
 * 과거 row 의 의미가 달라지므로 이름 변경은 마이그레이션을 동반한다.
 */
enum class VerdictOutcome { PASS, REJECT, UNCERTAIN, UNVERIFIABLE, ERROR }
