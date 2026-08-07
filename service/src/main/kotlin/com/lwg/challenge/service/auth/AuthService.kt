package com.lwg.challenge.service.auth

import com.lwg.challenge.core.auth.JwtTokenProvider
import com.lwg.challenge.core.hash.PhoneHasher
import com.lwg.challenge.core.hash.RefreshTokenHasher
import com.lwg.challenge.domain.auth.KakaoAuthPort
import com.lwg.challenge.domain.auth.KakaoServerException
import com.lwg.challenge.domain.auth.KakaoTokenInvalidException
import com.lwg.challenge.domain.auth.KakaoUserInfo
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.DialogException
import com.lwg.challenge.domain.common.exception.FullScreenException
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.user.UserStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 카카오 로그인 + Refresh Token Rotation 서비스.
 *
 * 1. 모바일이 SDK로 받은 access_token 그대로 수신
 * 2. KakaoAuthPort 로 사용자 정보 조회
 * 3. UserRepository 로 upsert (kakao_id 기준)
 * 4. phone_number 는 SHA-256 해시 저장
 * 5. 자체 JWT access/refresh 토큰 발급 + refresh hash DB 저장
 *
 * refresh() 는 JWT 검증 + DB hash 일치 확인 후 새 토큰 쌍을 발급하고 hash 를 회전한다.
 * 이전 refresh 는 hash 가 덮어씌워지는 즉시 무효화된다.
 */
@Service
class AuthService(
    private val kakaoAuthPort: KakaoAuthPort,
    private val userRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(AuthService::class.java)

    @Transactional
    fun loginWithKakao(kakaoAccessToken: String): LoginResult {
        val kakaoUser: KakaoUserInfo = mapKakaoExceptions("사용자 정보 조회") {
            kakaoAuthPort.getUserInfo(kakaoAccessToken)
        }

        val phoneHash: String? = kakaoUser.rawPhoneNumber
            ?.takeIf { it.isNotBlank() }
            ?.let { PhoneHasher.hashPhone(it) }
        val phoneVerified: Boolean = phoneHash != null

        val nickname: String = (kakaoUser.nickname?.takeIf { it.isNotBlank() }
            ?: "사용자${kakaoUser.kakaoId}").take(NICKNAME_MAX_LENGTH)
        val profileImageUrl: String? = kakaoUser.profileImageUrl

        val existing: User? = userRepository.findByKakaoId(kakaoUser.kakaoId)
        val now = LocalDateTime.now(clock)

        val (saved: User, isNewUser: Boolean) = if (existing == null) {
            val newUser = User(
                id = null,
                kakaoId = kakaoUser.kakaoId,
                nickname = nickname,
                profileImageUrl = profileImageUrl,
                phoneNumber = phoneHash,
                phoneVerified = phoneVerified,
                fcmToken = null,
                status = UserStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            )
            userRepository.save(newUser) to true
        } else {
            val merged = existing.copy(
                nickname = nickname,
                profileImageUrl = profileImageUrl,
                phoneNumber = phoneHash ?: existing.phoneNumber,
                phoneVerified = if (phoneHash != null) true else existing.phoneVerified,
                updatedAt = now,
            )
            userRepository.save(merged) to false
        }

        val userId: Long = saved.id ?: error("saved user id is null")
        if (isNewUser) {
            log.info("신규 사용자 가입: userId={}, kakaoId={}", userId, kakaoUser.kakaoId)
        } else {
            log.info("기존 사용자 로그인: userId={}, kakaoId={}", userId, kakaoUser.kakaoId)
        }

        val tokens = issueTokens(userId)

        return LoginResult(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            userId = userId,
            isNewUser = isNewUser,
        )
    }

    /**
     * [userId] 에 대해 access + refresh 를 발급하고 refresh hash 를 저장한다 (ADR-0009 rotation).
     *
     * **카카오 로그인과 개발용 테스트 로그인이 이 메서드를 공유한다.**
     * 별도 발급 로직을 만들면 `refresh_token_hash` 저장이 갈라져 rotation 이 한쪽에서만 동작하고,
     * 토큰 형태도 달라져 모바일이 후처리를 두 벌 만들어야 한다 (dev-test-login spec 요구사항).
     */
    @Transactional
    fun issueTokens(userId: Long): IssuedTokens {
        val accessToken = jwtTokenProvider.generateAccessToken(userId)
        val refreshToken = jwtTokenProvider.generateRefreshToken(userId)

        userRepository.updateRefreshTokenHash(
            userId = userId,
            hash = RefreshTokenHasher.hash(refreshToken),
            issuedAt = LocalDateTime.now(clock),
        )

        return IssuedTokens(accessToken = accessToken, refreshToken = refreshToken)
    }

    /**
     * Refresh Token Rotation.
     *
     * 1. JWT 서명/exp + tokenType 검증.
     * 2. DB 에 저장된 hash 와 들어온 토큰의 hash 가 일치하는지 확인 (이미 회전된 옛 토큰 차단).
     * 3. 검증 통과 시 새 access + 새 refresh 발급, 새 refresh hash 로 회전.
     *
     * 어떤 검증이든 실패하면 정보 노출 최소화를 위해 동일 메시지의 UnauthorizedException 을 던진다.
     */
    @Transactional
    fun refresh(refreshToken: String): RefreshResult {
        val userId = jwtTokenProvider.verifyAndGetUserId(
            refreshToken,
            expectedType = JwtTokenProvider.TOKEN_TYPE_REFRESH,
        ) ?: throw UnauthorizedException(REFRESH_INVALID_MESSAGE)

        val user = userRepository.findById(userId)
            ?: throw UnauthorizedException(REFRESH_INVALID_MESSAGE)

        val incomingHash = RefreshTokenHasher.hash(refreshToken)
        if (user.refreshTokenHash == null || user.refreshTokenHash != incomingHash) {
            throw UnauthorizedException(REFRESH_INVALID_MESSAGE)
        }

        val newAccessToken = jwtTokenProvider.generateAccessToken(userId)
        val newRefreshToken = jwtTokenProvider.generateRefreshToken(userId)

        userRepository.updateRefreshTokenHash(
            userId = userId,
            hash = RefreshTokenHasher.hash(newRefreshToken),
            issuedAt = LocalDateTime.now(clock),
        )

        log.info("refresh token rotated: userId={}", userId)

        return RefreshResult(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken,
        )
    }

    /**
     * 로그아웃 (push-fcm T-B2). 서버가 쥐고 있는 **기기 귀속 상태 두 개**를 함께 끊는다.
     *
     * 1. `refresh_token_hash = NULL` — ADR-0009 rotation 무효화. 이후 그 기기의 refresh 는
     *    hash 불일치로 401 을 받고 재로그인으로 떨어진다.
     * 2. `fcm_token = NULL` — 그 기기로 이 계정 앞으로 오던 푸시가 더는 가지 않는다.
     *
     * **둘을 한 메서드에 묶는 것이 핵심이다.** 하나만 끊으면 "로그아웃했는데 알림은 계속 온다"
     * 또는 "알림은 끊겼는데 세션은 살아 있다"는 어긋난 상태가 남는다.
     *
     * **멱등하다** — 이미 로그아웃된 사용자를 다시 불러도, 심지어 탈퇴로 row 가 사라졌어도 예외
     * 없이 성공한다. 두 UPDATE 모두 조건 없는 NULL 대입이라 재실행이 무해하고, 영향 row 0 건은
     * "이미 그 상태"와 구분할 필요가 없다. 🔴 여기서 404/401 을 던지면 **로그아웃이 실패해
     * 클라이언트가 로그인 상태에 갇힌다** — 로그아웃은 언제나 성공해야 하는 연산이다.
     */
    @Transactional
    fun logout(userId: Long) {
        userRepository.updateRefreshTokenHash(userId, hash = null, issuedAt = null)
        userRepository.updateFcmToken(userId, fcmToken = null)
        log.info("로그아웃: userId={} (refresh hash + fcm token 무효화)", userId)
    }

    /**
     * 카카오 호출 예외를 우리 에러 응답 체계로 변환.
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

    companion object {
        private const val NICKNAME_MAX_LENGTH = 50
        private const val REFRESH_INVALID_MESSAGE = "Refresh Token 이 유효하지 않거나 만료되었습니다"
    }
}
