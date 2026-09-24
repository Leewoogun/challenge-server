package com.lwg.challenge.domain.verification

/**
 * 미션-이미지 타당성 검증 포트 (ai-verification T-B1, ADR-0013).
 *
 * 구현체는 `:infra:external` 의 `GeminiMissionVerifier` / `NoOpMissionVerifier` 이고,
 * `NotificationSender` ↔ `FcmNotificationSender`/`NoOpNotificationSender` 와 **같은 구조**다.
 * 제공자를 바꾸면 이 포트 뒤의 구현체만 갈아 끼운다 — ADR-0013 §근거 3-ⓒ 가 모델 문자열을
 * ADR 에 박지 않은 이유이기도 하다(*"지금 고정할 것은 무엇 뒤에 두느냐이지 지금 무엇을
 * 쓰느냐가 아니다"*).
 *
 * ## 🔴 이 포트는 예외를 던지지 않는다
 *
 * 호출 실패 · 타임아웃 · 스키마 파싱 실패는 전부 [MissionVerdict.error] 로 **값으로** 돌려준다.
 * 던지면 호출부마다 try/catch 를 달아야 하고, **한 곳이 빠지는 날 그날 판정 배치가 멈춘다.**
 * 실패를 값으로 만들면 fail-open 이 타입에서 강제된다.
 *
 * ⚠️ 특히 **`429 RESOURCE_EXHAUSTED`(선불 잔액 소진/만료)도 여기로 들어온다.** 그건 장애가
 * 아니라 *"돈이 떨어졌다"* 이고, 그때 배치가 멈추는 것은 훨씬 나쁜 실패다. 대신 그 사실이
 * 묻히지 않도록 **배치 요약이 스킵 건수를 숫자로 남긴다** (ADR-0013 §결정 4 대가).
 *
 * ## 🔴 트랜잭션 안에서 부르지 마라
 *
 * 외부 HTTP 호출이다. DB 트랜잭션 안에서 부르면 네트워크 I/O 가 끝날 때까지 커넥션을 붙잡는다.
 * `MissionVerificationRunner` 가 **트랜잭션 밖에서** 부르고, 결과 기록만 짧은 트랜잭션으로 연다.
 */
interface MissionVerifier {

    /**
     * [photoBytes] 가 [missionText] 를 수행한 증거로 타당한지 판정한다.
     *
     * @param missionText 사용자가 자유 텍스트로 쓴 미션. **사진으로 검증 불가능한 미션이 섞여
     *   들어온다** — 구현체는 판정하기 전에 *"판정할 수 있는 종류인지"* 를 먼저 판단해야 한다
     *   ([VerdictOutcome.UNVERIFIABLE]).
     * @param photoBytes JPEG 바이트. `PhotoStorage.read(key)` 결과를 그대로 넘긴다.
     *   🔴 **이 바이트가 서버 밖으로 나간다** — 이 프로젝트에서 사진이 제3자에게 전송되는
     *   유일한 지점이다 (ADR-0011 이 닫아 둔 경계를 ADR-0013 이 연다).
     */
    fun verify(missionText: String, photoBytes: ByteArray): MissionVerdict
}
