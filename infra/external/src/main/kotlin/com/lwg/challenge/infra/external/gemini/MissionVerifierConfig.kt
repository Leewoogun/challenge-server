package com.lwg.challenge.infra.external.gemini

import com.lwg.challenge.domain.verification.MissionVerifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * [MissionVerifier] 빈 선택 (ai-verification T-B1, ADR-0013 §결정 4·5).
 *
 * ```
 * api-key 와 model 이 둘 다 있음 → GeminiMissionVerifier  (실제 판별)
 * 그 외 전부                      → NoOpMissionVerifier    (항상 통과)
 * ```
 *
 * ## 왜 `@ConditionalOnBean`/`@ConditionalOnProperty` 가 아니라 런타임 분기인가
 *
 * `NotificationSenderConfig` KDoc 이 근거를 이미 적어 뒀고 **그대로 승계한다**:
 * `@ConditionalOnBean` 은 **평가 순서에 의존**해 등록 순서가 조금만 바뀌어도 **조용히 no-op 이
 * 선택**될 수 있고(공식 문서도 auto-configuration 밖에서는 신뢰하지 말라고 못박는다),
 * `@ConditionalOnProperty` 는 *"키를 설정하긴 했는데 값이 잘못됐다"* 를 구분하지 못한다.
 *
 * **빈 하나가 런타임에 스스로 판단하면 두 문제가 다 사라진다.** 분기가 한 함수에 모여 있어
 * 읽는 사람이 순서를 추론할 필요도 없다.
 *
 * ⚠️ 여기서 `dev-test-login` 의 fail-closed 커스텀 `Condition` 을 흉내 내지 마라 — 그쪽은
 * **보안 경계**라 닫히는 쪽이 안전했지만, 여기는 **기능 저하**라 판단 지점이 다르다.
 *
 * ## 🔴 이 메서드는 예외를 던지지 않는다
 *
 * 던지면 **빈 생성 실패 → 컨텍스트 로드 실패 → 서버가 안 뜬다.** AI 설정 오류가 서비스 전체를
 * 내리는 것은 실패 모드로 과하다 — 판별이 꺼져도 챌린지는 끝까지 돌아간다.
 */
@Configuration
class MissionVerifierConfig(
    /**
     * Gemini API 키. 기본값은 빈 문자열(미설정)이고 **그게 지금 정상 상태다.**
     *
     * ⚠️ 타입이 `String` 인 것이 의도다. 변환 대상 타입으로 받으면 오타에
     * `ConversionFailedException` 이 나서 **서버가 기동 중 죽는다** (dev-test-login 실측).
     * 문자열로 받아 검증은 우리가 한다.
     *
     * 🔴 키는 **레포 밖**에 둔다. `.gitignore` 는 실수 방어이지 보관 위치가 아니다.
     * 🔴 **유료 티어 키여야 한다** — 무료 티어는 보낸 사진이 구글 제품 개선에 사용된다
     * (ADR-0013 §근거 1). 키를 만들기만 하면 free tier 이므로 콘솔에서 `Paid` 표시를 눈으로
     * 확인해야 한다. 이 코드는 티어를 알 수 없다.
     */
    @Value("\${gemini.api-key:}") private val apiKey: String,

    /**
     * 모델 ID. **가격표 표기가 아니라 API 식별자**다 (`gemini-3.5-flash`).
     *
     * 🔴 `gemini-3.5-flash` 와 `gemini-3.5-flash-lite` 는 **다른 모델이다** — 입력 5배,
     * 출력 3.6배. 이름이 한 단어 차이라 실제로 혼동 사례가 나왔다 (ADR-0013 §측정 표 경고).
     *
     * 🔵 **모델 교체에 코드 변경도 ADR 개정도 필요 없다** (ADR-0013 §결정 5). 고정하는 것은
     * 제공자와 호출 형태이지 모델 문자열이 아니다. 다만 **내릴 때는 근거가 필요하다** —
     * 실사진 A/B 세트 없이 움직이지 마라 (ADR-0013 §확정 경위).
     */
    @Value("\${gemini.model:}") private val model: String,

    /**
     * 추론 강도. `minimal` / `low` / `medium` / `high` (모델별 지원 범위가 다르다).
     *
     * 🔴 **비용 지배 인자다.** Gemini 3.x 는 내부 사고 토큰이 **출력 요율로 과금**되는데
     * (가격표 문구가 문자 그대로 *"Output price (including thinking tokens)"*), 우리 출력은
     * JSON 한 줄(~150 토큰)이라 사고 토큰이 비용의 대부분을 차지한다. 같은 모델 안에서
     * **4.6배**가 움직인다 (ADR-0013 §측정 표).
     *
     * 기본값 `low` 는 2026-09-20 사용자 확정값이다. **올릴 이유가 생기면 판별률을 근거로
     * 올린다** — 기본값이어서 올라가면 안 된다.
     */
    @Value("\${gemini.thinking-level:low}") private val thinkingLevel: String,
) {

    private val log = LoggerFactory.getLogger(MissionVerifierConfig::class.java)

    @Bean
    fun missionVerifier(): MissionVerifier =
        if (apiKey.isBlank() || model.isBlank()) {
            // 🔴 어느 쪽이 비었는지 남긴다 — "왜 AI 가 안 도나" 를 추적할 유일한 단서다.
            log.info(
                "AI 인증 판별 비활성 — NoOpMissionVerifier 를 사용합니다 (제출·판정은 정상 동작). " +
                    "gemini.api-key 설정됨={}, gemini.model='{}'",
                apiKey.isNotBlank(),
                model,
            )
            NoOpMissionVerifier()
        } else {
            log.info("AI 인증 판별 활성 — GeminiMissionVerifier(model={}, thinkingLevel={})", model, thinkingLevel)
            GeminiMissionVerifier(apiKey = apiKey, model = model, thinkingLevel = thinkingLevel)
        }
}
