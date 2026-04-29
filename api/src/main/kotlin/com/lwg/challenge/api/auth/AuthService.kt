package com.lwg.challenge.api.auth

import com.lwg.challenge.api.auth.dto.LoginData
import com.lwg.challenge.api.common.ResponseCode
import com.lwg.challenge.api.common.exception.DialogException
import com.lwg.challenge.api.common.exception.FullScreenException
import com.lwg.challenge.core.hash.PhoneHasher
import com.lwg.challenge.infra.auth.UserEntity
import com.lwg.challenge.infra.auth.UserJpaRepository
import com.lwg.challenge.infra.kakao.KakaoOAuthClient
import com.lwg.challenge.infra.kakao.KakaoServerException
import com.lwg.challenge.infra.kakao.KakaoTokenInvalidException
import com.lwg.challenge.infra.kakao.KakaoUserResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 카카오 로그인 서비스 (Authorization Code Flow).
 *
 * 1. authorization code → access_token 교환 (`/oauth/token`)
 * 2. access_token으로 사용자 정보 조회 (`/v2/user/me`)
 * 3. kakao_id로 users 조회 → 신규면 INSERT, 기존이면 UPDATE (nickname, profile, phone)
 * 4. phone_number는 SHA-256(정규화된 번호) 저장 (ADR-0008)
 * 5. 자체 JWT access/refresh 토큰 발급
 *
 * 카카오의 access_token / refresh_token은 보관하지 않는다 — 사용자 정보 조회 한 번에만 쓰고 버린다.
 */
@Service
class AuthService(
    private val kakaoOAuthClient: KakaoOAuthClient,
    private val userRepository: UserJpaRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    @Value("\${kakao.redirect-uri}") private val redirectUri: String,
) {

    private val log = LoggerFactory.getLogger(AuthService::class.java)

    @Transactional
    fun loginWithKakao(code: String): LoginData {
        // 1. code → access_token 교환
        val kakaoToken = mapKakaoExceptions("토큰 교환") {
            kakaoOAuthClient.exchangeCodeForToken(code, redirectUri)
        }

        // 2. access_token → 사용자 정보
        val kakaoUser: KakaoUserResponse = mapKakaoExceptions("사용자 정보 조회") {
            kakaoOAuthClient.getUserInfo(kakaoToken.accessToken)
        }

        // 3. phone 처리
        val phoneHash: String? = extractPhoneHash(kakaoUser)
        val phoneVerified: Boolean = phoneHash != null

        val nickname: String = (kakaoUser.kakaoAccount?.profile?.nickname?.takeIf { it.isNotBlank() }
            ?: "사용자${kakaoUser.id}").take(NICKNAME_MAX_LENGTH)
        val profileImageUrl: String? = kakaoUser.kakaoAccount?.profile?.profileImageUrl

        // 4. users upsert
        val existing: UserEntity? = userRepository.findByKakaoId(kakaoUser.id)
        val isNewUser: Boolean
        val userId: Long
        if (existing == null) {
            val now = LocalDateTime.now()
            val newEntity = UserEntity(
                kakaoId = kakaoUser.id,
                nickname = nickname,
                profileImageUrl = profileImageUrl,
                phoneNumber = phoneHash,
                phoneVerified = phoneVerified,
                createdAt = now,
                updatedAt = now,
            )
            val saved = userRepository.save(newEntity)
            userId = saved.id ?: error("saved user id is null")
            isNewUser = true
            log.info("신규 사용자 가입: userId={}, kakaoId={}", userId, kakaoUser.id)
        } else {
            existing.nickname = nickname
            existing.profileImageUrl = profileImageUrl
            // phone은 받은 경우에만 갱신 (이미 있는 번호를 null로 덮어쓰지 않음)
            if (phoneHash != null) {
                existing.phoneNumber = phoneHash
                existing.phoneVerified = true
            }
            userRepository.save(existing)
            userId = existing.id ?: error("existing user id is null")
            isNewUser = false
            log.info("기존 사용자 로그인: userId={}, kakaoId={}", userId, kakaoUser.id)
        }

        // 5. 자체 JWT 발급
        val accessToken = jwtTokenProvider.generateAccessToken(userId)
        val refreshToken = jwtTokenProvider.generateRefreshToken(userId)

        return LoginData(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            isNewUser = isNewUser,
        )
    }

    /**
     * 카카오 호출 예외를 우리 에러 응답 체계로 변환.
     * - Token invalid (4xx) → 다이얼로그 (재로그인 유도)
     * - Server error (5xx/network) → 풀스크린 (일시 장애 안내)
     */
    private inline fun <T> mapKakaoExceptions(stage: String, action: () -> T): T = try {
        action()
    } catch (e: KakaoTokenInvalidException) {
        log.info("Kakao {} 실패 (token invalid): {}", stage, e.message)
        throw DialogException("카카오 로그인이 만료되었습니다. 다시 시도해주세요")
    } catch (e: KakaoServerException) {
        log.warn("Kakao {} 실패 (server): {}", stage, e.message)
        throw FullScreenException(
            code = ResponseCode.FULL_SCREEN_ERROR_B,
            message = "일시적인 장애로 로그인할 수 없습니다",
        )
    }

    /**
     * Kakao 응답에서 phone_number를 정규화 + SHA-256 해시로 변환.
     * 동의 거부(needs_agreement=true)이거나 필드 없으면 null.
     */
    private fun extractPhoneHash(kakaoUser: KakaoUserResponse): String? {
        val account = kakaoUser.kakaoAccount ?: return null
        if (account.phoneNumberNeedsAgreement == true) return null
        val raw = account.phoneNumber?.takeIf { it.isNotBlank() } ?: return null
        return PhoneHasher.hashPhone(raw)
    }

    companion object {
        // users.nickname VARCHAR(50) — 카카오가 더 긴 닉네임을 줘도 안전하게 자른다.
        private const val NICKNAME_MAX_LENGTH = 50
    }
}
