package com.lwg.challenge.service.auth

import com.lwg.challenge.core.auth.JwtTokenProvider
import com.lwg.challenge.domain.auth.KakaoAuthPort
import com.lwg.challenge.domain.auth.KakaoUserInfo
import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.service.user.FakeUserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * `AuthService.logout` 단위 테스트 (push-fcm T-B2).
 *
 * foundation Sprint 0 이후 스텁으로 남아 있던 엔드포인트의 실구현이다
 * (`docs/features/foundation/backend-report.md:86` 의 미구현 TODO).
 *
 * 검증하는 것은 **두 컬럼이 함께 끊기는가**와 **몇 번을 불러도 안전한가** 둘이다.
 * 하나만 끊기면 "로그아웃했는데 알림은 계속 온다" 또는 그 반대의 어긋난 상태가 남는다.
 */
class AuthServiceLogoutTest {

    private val repository = FakeUserRepository()

    private val service = AuthService(
        kakaoAuthPort = NoopKakaoAuthPort,
        userRepository = repository,
        // logout 은 JWT 를 만들지도 검증하지도 않는다. 생성자 요구를 채우기 위한 실물이다.
        jwtTokenProvider = JwtTokenProvider(
            secret = "test-secret-key-for-hs256-at-least-32-bytes-long",
            accessTokenExpirationMs = 3_600_000L,
            refreshTokenExpirationMs = 1_209_600_000L,
        ),
        clock = Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC),
    )

    private companion object {
        const val ME = 1L
    }

    private fun givenLoggedInUser() {
        repository.put(
            User(
                id = ME,
                kakaoId = 1001L,
                nickname = "이우건",
                profileImageUrl = null,
                phoneNumber = null,
                phoneVerified = false,
                fcmToken = "fcm-token-of-this-device",
                status = UserStatus.ACTIVE,
                createdAt = LocalDateTime.of(2026, 8, 6, 0, 0),
                updatedAt = LocalDateTime.of(2026, 8, 6, 0, 0),
                refreshTokenHash = "a".repeat(64),
                refreshTokenIssuedAt = LocalDateTime.of(2026, 8, 6, 0, 0),
            ),
        )
    }

    /** 🔴 이 feature 가 흡수한 미구현 TODO 의 본체 — 두 컬럼이 **함께** NULL 이 돼야 한다. */
    @Test
    fun `로그아웃하면 refresh hash 와 fcm token 이 둘 다 NULL 이 된다`() {
        givenLoggedInUser()

        service.logout(ME)

        assertNull(repository.refreshHashOf(ME), "refresh hash 가 남으면 세션이 살아 있다 (ADR-0009)")
        assertNull(repository.fcmTokenOf(ME), "fcm token 이 남으면 그 기기로 알림이 계속 간다")
    }

    @Test
    fun `이미 로그아웃된 사용자를 다시 로그아웃해도 예외가 없다 - 멱등`() {
        givenLoggedInUser()
        service.logout(ME)

        assertDoesNotThrow { service.logout(ME) }

        assertNull(repository.refreshHashOf(ME))
        assertNull(repository.fcmTokenOf(ME))
    }

    /**
     * 탈퇴 등으로 row 가 사라졌는데 access token 이 아직 유효한 경우.
     *
     * 🔴 **여기서 예외를 던지면 로그아웃이 실패해 클라이언트가 로그인 상태에 갇힌다.**
     * `updateFcmToken` 이 0 행을 돌려주는 상황을 [com.lwg.challenge.service.user.UserService] 는
     * 401 로 올리지만 로그아웃은 삼켜야 한다 — 같은 신호에 반대로 반응하는 것이 의도된 설계다.
     */
    @Test
    fun `존재하지 않는 사용자를 로그아웃해도 예외가 없다`() {
        assertDoesNotThrow { service.logout(9999L) }
    }

    @Test
    fun `두 컬럼 갱신이 모두 호출된다`() {
        givenLoggedInUser()

        service.logout(ME)

        assertEquals(
            listOf(
                "updateRefreshTokenHash($ME, hash=null)",
                "updateFcmToken($ME, token=null)",
            ),
            repository.calls,
        )
    }

    private object NoopKakaoAuthPort : KakaoAuthPort {
        override fun getUserInfo(accessToken: String): KakaoUserInfo =
            error("logout 경로는 카카오를 호출하지 않는다")
    }
}
