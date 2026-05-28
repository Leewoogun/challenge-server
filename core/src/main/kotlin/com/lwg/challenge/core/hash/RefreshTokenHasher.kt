package com.lwg.challenge.core.hash

import java.security.MessageDigest

/**
 * Refresh Token 의 sha256 hex 해시 유틸.
 *
 * `users.refresh_token_hash` 컬럼에 저장될 값을 생성한다.
 *
 * - 입력이 JWT (서명 포함 고엔트로피 문자열) 이므로 salt 없이 sha256 만으로 충분하다.
 *   비밀번호 hash 가 아니라 "이 토큰이 우리가 발급한 그 토큰인가" 의 lookup key 용도.
 * - 반환은 항상 64자 lower-case hex.
 */
object RefreshTokenHasher {

    /** raw refresh token → SHA-256 hex (64자, lower-case). */
    fun hash(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawToken.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
