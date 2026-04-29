package com.lwg.challenge.core.hash

import java.security.MessageDigest

/**
 * 전화번호 정규화 + SHA-256 해시 유틸.
 *
 * ADR-0008: `users.phone_number`는 평문이 아닌 SHA-256(정규화된 번호) hex 문자열로 저장.
 * 이 유틸을 `auth-kakao`와 추후 `/friends/sync-contacts`가 공통으로 사용해야 같은 값이 나온다.
 *
 * 정규화 규칙:
 * - 공백/하이픈(`-`)/점(`.`) 제거
 * - 이미 `+`로 시작 → 그대로 유지 (E.164 가정)
 * - `0`으로 시작하는 한국 번호 (`010...`) → 앞의 `0` 제거하고 `+82` prefix
 * - 그 외 숫자로만 시작하면 그대로 (국가 미상)
 *
 * 예:
 * - `+82 10-1234-5678` → `+821012345678`
 * - `010-1234-5678`    → `+821012345678`
 * - `01012345678`      → `+821012345678`
 */
object PhoneHasher {

    /** 정규화만 수행. 해시 전 단계. */
    fun normalize(raw: String): String {
        val stripped = raw.replace(" ", "")
            .replace("-", "")
            .replace(".", "")
            .replace("(", "")
            .replace(")", "")
        return when {
            stripped.isEmpty() -> stripped
            stripped.startsWith("+") -> stripped
            stripped.startsWith("0") -> "+82" + stripped.removePrefix("0")
            else -> stripped
        }
    }

    /** 정규화 + SHA-256 hex (64자). */
    fun hashPhone(raw: String): String {
        val normalized = normalize(raw)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
