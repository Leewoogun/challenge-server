package com.lwg.challenge.infra.external.firebase

import com.google.auth.oauth2.GoogleCredentials
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 서비스 계정 비공개 키를 읽어 [GoogleCredentials] 를 만든다. **어떤 경우에도 예외를 던지지 않는다.**
 *
 * ## 🔴 이 클래스의 존재 이유는 "안 터지는 것" 하나다
 *
 * `dev-test-login` 에서 `getProperty(Boolean::class)` 가 오타 하나에 `ConversionFailedException` 을
 * 던져 **서버 전체를 기동 실패시켰다.** 그 교훈이 계약 §0.4 에 명문화돼 있다 —
 * **fail-safe 는 "안 열린다"여야지 "터진다"가 아니다.**
 *
 * 여기서 터질 수 있는 경로가 특히 많다:
 * ```
 * 경로 미설정         → null 반환 (가장 흔한 경우. 아직 키가 없다)
 * 파일 없음           → FileNotFoundException  ← 삼킨다
 * 디렉터리를 가리킴    → IOException            ← 삼킨다
 * 읽기 권한 없음      → AccessDeniedException  ← 삼킨다
 * 손상된 JSON        → IOException            ← 삼킨다
 * JSON 은 맞지만 서비스 계정 형식이 아님 → IOException ← 삼킨다
 * ```
 * 전부 **null 로 수렴**시켜 호출부가 no-op 발송기를 고르게 한다. 알림이 안 가는 것은 불편이지만
 * 서버가 안 뜨는 것은 전면 장애다.
 *
 * ⚠️ **실패를 조용히 넘기지는 않는다** — WARN 으로 남긴다. "설정했는데 왜 안 가지?" 를 로그로
 * 추적할 수 있어야 한다. 다만 **경로 미설정은 INFO** 다. 아직 키가 없는 게 현재 정상 상태이고,
 * 정상 상태에 WARN 을 남기면 로그가 늑대소년이 된다.
 */
object FirebaseCredentialsLoader {

    private val log = LoggerFactory.getLogger(FirebaseCredentialsLoader::class.java)

    /**
     * @param path 서비스 계정 JSON 의 절대 경로. null/blank 면 "미설정" 으로 본다.
     * @return 성공 시 [GoogleCredentials], **그 외 전부 null**.
     */
    fun load(path: String?): GoogleCredentials? {
        val trimmed = path?.trim()
        if (trimmed.isNullOrEmpty()) {
            log.info("FCM 서비스 계정 키 경로가 설정되지 않았습니다. 푸시 발송을 건너뜁니다 (no-op).")
            return null
        }

        return runCatching {
            val file = File(trimmed)
            // 존재/파일 여부를 먼저 본다 — 예외 메시지보다 이쪽이 원인을 명확히 알려준다.
            if (!file.isFile) {
                log.warn("FCM 서비스 계정 키가 파일이 아닙니다: {} (없거나 디렉터리). 푸시 발송을 건너뜁니다.", trimmed)
                return null
            }
            file.inputStream().use { GoogleCredentials.fromStream(it) }
        }.onFailure { e ->
            // 손상된 JSON / 권한 문제 / 형식 불일치 전부 여기로 온다.
            log.warn(
                "FCM 서비스 계정 키를 읽지 못했습니다: {} ({}). 푸시 발송을 건너뜁니다.",
                trimmed,
                e.message,
            )
        }.getOrNull()
    }
}
