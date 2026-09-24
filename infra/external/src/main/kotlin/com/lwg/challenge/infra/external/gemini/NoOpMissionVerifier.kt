package com.lwg.challenge.infra.external.gemini

import com.lwg.challenge.domain.verification.MissionVerdict
import com.lwg.challenge.domain.verification.MissionVerifier

/**
 * 검증 비활성 구현체 — **항상 통과**시킨다 (ADR-0013 §결정 4).
 *
 * `NoOpNotificationSender` 와 같은 자리다: **API 키가 없어도 서버가 뜨고 전 테스트가 돌아야
 * 한다.** `push-fcm` 이 그 격리 덕분에 Firebase 프로젝트를 기다리지 않고 백엔드를 완성했고,
 * 여기서도 Gemini 키 발급이 구현의 선행조건이 아니다.
 *
 * 🔴 이 구현체가 선택되면 기능이 **이 feature 이전과 정확히 동일하게** 동작한다 —
 * 제출하면 인정이다. 그것이 fail-open 의 마지막 겹이자, 사고 시 **설정 한 줄로 기능 전체를
 * 끄는 수단**이다.
 *
 * ## 🔴 `PASS` 가 아니라 [MissionVerdict.unverifiable] 을 돌려준다
 *
 * `NoOpNotificationSender` 가 `SKIPPED` 를 주지 `FAILED` 를 주지 않는 것과 같은 판단이다.
 * `PASS` 로 주면 `verifications.ai_verdict` 에서 **"AI 가 보고 통과시켰다"** 와
 * **"AI 를 아예 안 불렀다"** 가 구분되지 않는다. 그 구분이 사라지면 오탐 신고가 들어왔을 때
 * 그 건이 판정을 받은 건지조차 알 수 없고, 컬럼을 남긴 이유(V13 주석)가 통째로 무효가 된다.
 *
 * ⚠️ 실제로는 [MissionVerificationRunner][com.lwg.challenge.domain.verification.MissionVerifier]
 * 호출부가 NoOp 일 때도 결과를 기록하므로, 키 없는 기간의 row 는 전부 `UNVERIFIABLE` 로 남는다.
 * 그게 *"그 기간에는 판별이 꺼져 있었다"* 의 증거다.
 */
class NoOpMissionVerifier : MissionVerifier {

    override fun verify(missionText: String, photoBytes: ByteArray): MissionVerdict =
        MissionVerdict.unverifiable(REASON)

    companion object {
        /**
         * **운영자가 DB 를 볼 때** *"이 기간엔 AI 가 꺼져 있었다"* 를 읽는 용도의 문자열이다.
         * `ai_reason` 컬럼에 저장된다.
         *
         * ## 🔴 사용자에게 보이지 않는 근거는 **wire 게이트 한 곳**이다
         *
         * `VerificationService.view()` 가 `Verification.rejectedByAi()` 가 참인 건에만
         * `aiReason` 을 싣는다. 그 판정은 `UNVERIFIABLE` 에 대해 false 이므로 이 문자열은
         * wire 에 나가지 않는다.
         *
         * ⚠️ **게이트를 떼면 이 문자열이 그대로 사용자 화면에 뜬다** — *"인증이 인정되지
         * 않았어요: AI 검증이 비활성 상태입니다 (API 키 미설정)"*, 그것도 **키가 없는 동안
         * 제출한 전건에.**
         *
         * 🔴 **2026-09-22 에 실제로 그 게이트가 없는 채로 이 KDoc 이 *"보이지 않는다"* 고
         * 단정하고 있었다**(`aiverify-mobile` 적발). 컴파일도 테스트도 초록이라 **조용했다.**
         * 그래서 이제 근거를 단정하지 않고 **집행 지점을 가리킨다** —
         * `VerificationServiceTest` 의 *"AI 를 부르지 못한 건의 사유는 wire 에 실리지 않는다"*
         * 가 이 문자열의 비노출을 직접 단언한다.
         *
         * ## ⚠️ 이 문자열이 **합쇼체**인 것도 게이트에 매달려 있다
         *
         * 사용자 노출 사유는 **해요체**여야 한다 — 앱이 붙이는 접두어가
         * *"인증이 인정되지 않았어요:"* 이고 그 화면 문구가 전부 해요체다. 이 문자열은
         * **운영자가 DB 에서 읽는 값**이라 합쇼체로 둔다.
         *
         * 🔴 **게이트를 떼거나 이 판정이 `REJECT` 로 바뀌면 사용자 화면에 나가고, 그 순간
         * 어체가 접두어와 어긋난다. 그때 해요체로 고쳐라**
         * (*"AI 검증이 비활성 상태예요"*). 🔑 **조건을 명시하고 조건이 깨질 때 할 일을 적는다** —
         * 위 문단이 *"보이지 않는다"* 를 단정했다가 거짓이었던 것과 같은 실수를 반복하지 않기 위해서다.
         */
        const val REASON = "AI 검증이 비활성 상태입니다 (API 키 미설정)"
    }
}
