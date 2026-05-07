package com.lwg.challenge.core.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import javax.crypto.SecretKey

/**
 * JWT 발급·검증 유틸. HS256.
 *
 * 토큰 구조:
 * - Access Token: sub=userId, claims: { tokenType: "access" }, exp = 1h
 * - Refresh Token: sub=userId, claims: { tokenType: "refresh" }, exp = 14d
 *
 * secret은 `application.yml`의 `jwt.secret` (환경변수 JWT_SECRET로 오버라이드).
 */
@Component
class JwtTokenProvider(
    @Value("\${jwt.secret}") private val secret: String,
    @Value("\${jwt.access-token-expiration-ms}") private val accessTokenExpirationMs: Long,
    @Value("\${jwt.refresh-token-expiration-ms}") private val refreshTokenExpirationMs: Long,
) {

    private val key: SecretKey by lazy { Keys.hmacShaKeyFor(secret.toByteArray()) }

    fun generateAccessToken(userId: Long): String =
        buildToken(userId, TOKEN_TYPE_ACCESS, accessTokenExpirationMs)

    fun generateRefreshToken(userId: Long): String =
        buildToken(userId, TOKEN_TYPE_REFRESH, refreshTokenExpirationMs)

    /**
     * 토큰 검증. 만료·서명 불일치·타입 불일치 시 null 반환.
     * @param expectedType "access" 또는 "refresh"
     * @return 검증 성공 시 userId, 실패 시 null
     */
    fun verifyAndGetUserId(token: String, expectedType: String = TOKEN_TYPE_ACCESS): Long? {
        return try {
            val claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload

            val tokenType = claims["tokenType"] as? String
            if (tokenType != expectedType) return null

            claims.subject.toLong()
        } catch (e: Exception) {
            null
        }
    }

    private fun buildToken(userId: Long, tokenType: String, expirationMs: Long): String {
        val now = Date()
        val expiry = Date(now.time + expirationMs)
        return Jwts.builder()
            .subject(userId.toString())
            .claim("tokenType", tokenType)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    companion object {
        const val TOKEN_TYPE_ACCESS = "access"
        const val TOKEN_TYPE_REFRESH = "refresh"
    }
}
