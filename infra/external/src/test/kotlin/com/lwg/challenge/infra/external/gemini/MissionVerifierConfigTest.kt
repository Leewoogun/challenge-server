package com.lwg.challenge.infra.external.gemini

import com.lwg.challenge.domain.verification.VerdictOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * [MissionVerifierConfig] 의 검증기 선택 (ai-verification T-B1, ADR-0013 §결정 4).
 *
 * 🔴 **API 키가 없어도 빈 생성이 성공해야 한다.** 여기서 예외가 나면 컨텍스트 로드가 깨져
 * **서버가 아예 안 뜬다** — AI 설정 오류의 대가로는 과하다. `NotificationSenderConfigTest`
 * 와 같은 명제이며, 그쪽이 이 파일의 정본 선례다.
 *
 * 🔑 이 테스트들이 지키는 수용 기준은 *"API 키가 없어도 서버가 기동하고 전 테스트가
 * 통과한다"* (spec §3)이다. 개인 프로젝트라 CI 에 키가 없고, 키를 요구하기 시작하면
 * **키를 가진 사람만 빌드할 수 있는 레포**가 된다.
 */
class MissionVerifierConfigTest {

    private fun config(apiKey: String = "", model: String = "", thinkingLevel: String = "low") =
        MissionVerifierConfig(apiKey = apiKey, model = model, thinkingLevel = thinkingLevel)

    @Test
    fun `API 키가 비어 있으면 NoOpMissionVerifier 를 고른다`() {
        val verifier = assertDoesNotThrow { config(apiKey = "", model = "gemini-3.5-flash").missionVerifier() }

        assertInstanceOf(NoOpMissionVerifier::class.java, verifier)
    }

    /** 키만 있고 모델이 비면 부를 대상이 없다 — 절반만 설정된 상태도 NoOp 이다. */
    @Test
    fun `모델이 비어 있으면 키가 있어도 NoOpMissionVerifier 를 고른다`() {
        val verifier = assertDoesNotThrow { config(apiKey = "AIza-어떤키든", model = "").missionVerifier() }

        assertInstanceOf(NoOpMissionVerifier::class.java, verifier)
    }

    @Test
    fun `둘 다 설정되면 GeminiMissionVerifier 를 고른다`() {
        val verifier = config(apiKey = "AIza-어떤키든", model = "gemini-3.5-flash").missionVerifier()

        assertInstanceOf(GeminiMissionVerifier::class.java, verifier)
    }

    /**
     * 🔴 키가 없는 것은 **정상 상태**다. 예외를 던지면 서버 기동이 막힌다.
     *
     * ⚠️ 이 단언은 위 세 테스트와 겹쳐 보이지만 겹치지 않는다 — 위는 *"무엇을 골랐나"*,
     * 이건 *"고르는 행위가 터지지 않나"* 다. 나중에 Config 에 키 형식 검증 같은 걸 넣고 싶어지는
     * 날 이 테스트가 먼저 빨개진다.
     */
    @Test
    fun `키가 없어도 예외를 던지지 않는다 — 서버 기동을 막으면 안 된다`() {
        assertDoesNotThrow { config().missionVerifier() }
    }

    /**
     * NoOp 은 [VerdictOutcome.UNVERIFIABLE] 을 준다 — [VerdictOutcome.PASS] 가 아니다.
     *
     * 🔴 `NoOpNotificationSender` 가 `SKIPPED` 를 주지 `FAILED` 를 주지 않는 것과 같은 판단이다.
     * `PASS` 로 주면 **"AI 가 보고 통과시켰다"** 와 **"AI 를 아예 안 불렀다"** 가 `ai_verdict`
     * 컬럼에서 구분되지 않는다. 그 구분이 사라지면 rubric 튜닝의 근거(V13 주석)도 같이 사라진다.
     */
    @Test
    fun `NoOp 은 무엇을 받든 통과시키고 UNVERIFIABLE 로 기록된다`() {
        val verdict = NoOpMissionVerifier().verify("아무 미션", ByteArray(0))

        assertFalse(verdict.rejectsVerification())
        assertEquals(VerdictOutcome.UNVERIFIABLE, verdict.outcome)
    }
}
