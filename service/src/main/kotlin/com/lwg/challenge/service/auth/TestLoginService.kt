package com.lwg.challenge.service.auth

import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.core.config.TestLoginEnabledCondition
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 개발용 테스트 로그인 (dev-test-login T-B2).
 *
 * ## 🔴 인증 우회 경로다 — 격리가 기능과 동등한 수용 기준이다
 *
 * 카카오 검증 없이 JWT 를 발급한다. 운영에 새어 나가면 **누구나 임의 계정으로 로그인**할 수 있다.
 *
 * [TestLoginEnabledCondition] 으로 **속성을 명시적으로 켠 환경에서만 빈이 생성**된다.
 * 속성이 없으면 기본값 `false` 라 **끄는 게 아니라 켜야 열린다** — fail-closed.
 *
 * **Spring 프로파일을 쓰지 않은 이유** (api-contract §0.1): 프로파일은 시간이 지나며 의미가 누적된다.
 * 누군가 로깅·목데이터·CORS 때문에 `dev` 를 켜는 순간 **인증 우회까지 딸려 켜진다.**
 * spec 이 금지한 "운영에서 끄는 걸 잊으면 열리는" 구조의 변형이다. 단일 목적 속성이 그 사고를 막는다.
 *
 * ## 계정 규약
 *
 * | testUserNo | kakao_id  | 닉네임   |
 * |------------|-----------|---------|
 * | 1          | 999000001 | 테스터1  |
 * | 2          | 999000002 | 테스터2  |
 * | 3          | 999000003 | 테스터3  |
 *
 * `kakao_id 999000000+` 대역은 e2e 하네스가 쓰던 대역을 승계한다 — **실사용자와 한 쿼리로 분리**되고
 * 삭제도 그 조건 하나면 된다.
 */
@Service
@Conditional(TestLoginEnabledCondition::class)
class TestLoginService(
    private val userRepository: UserRepository,
    private val authService: AuthService,
) {

    /**
     * 테스트 계정으로 로그인한다. 계정이 없으면 만들고 있으면 재사용한다 (**멱등**).
     *
     * 토큰 발급은 [AuthService.issueTokens] 를 **그대로 재사용**한다 — 카카오 로그인과 동일한
     * 형태·동일한 rotation 이어야 "테스트 로그인으로 검증한 것이 실제 로그인을 보증"한다.
     *
     * `isNewUser` 는 **실제 값**이다(최초 생성 `true`, 재사용 `false`). 항상 `false` 로 고정하면
     * 나중에 온보딩이 생겼을 때 테스트 로그인으로 그 경로를 밟을 수 없어 자기모순이 된다.
     */
    @Transactional
    fun login(testUserNo: Int): LoginResult {
        if (testUserNo !in MIN_TEST_USER_NO..MAX_TEST_USER_NO) {
            throw SnackbarException("테스트 계정 번호는 ${MIN_TEST_USER_NO}~${MAX_TEST_USER_NO}이에요")
        }

        val kakaoId = KAKAO_ID_BASE + testUserNo
        val existing: User? = userRepository.findByKakaoId(kakaoId)

        val (user: User, isNewUser: Boolean) = if (existing == null) {
            val now = KstTime.now()
            userRepository.save(
                User(
                    id = null,
                    kakaoId = kakaoId,
                    nickname = nicknameFor(testUserNo),
                    profileImageUrl = null,
                    phoneNumber = null,
                    phoneVerified = false,
                    fcmToken = null,
                    status = UserStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                ),
            ) to true
        } else {
            // 멱등 — 이미 있으면 그대로 쓴다. 닉네임을 덮어쓰지 않는 이유: 손 테스트 중 DB 에서
            // 이름을 바꿔 구분하는 경우가 있는데 로그인할 때마다 되돌리면 그 작업이 무의미해진다.
            existing to false
        }

        val userId: Long = user.id ?: error("저장된 test user 의 id 가 null 일 수 없습니다")
        val tokens = authService.issueTokens(userId)

        return LoginResult(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            userId = userId,
            isNewUser = isNewUser,
        )
    }

    companion object {
        const val MIN_TEST_USER_NO = 1
        const val MAX_TEST_USER_NO = 3

        /** 실사용자와 분리되는 가짜 계정 대역. `kakao_id BETWEEN 999000001 AND 999000999` 로 일괄 삭제 가능. */
        const val KAKAO_ID_BASE = 999_000_000L

        /** 서버가 닉네임을 소유한다. 모바일 버튼 라벨도 **같은 문자열**을 쓴다 (api-contract 확정). */
        fun nicknameFor(testUserNo: Int): String = "테스터$testUserNo"
    }
}
