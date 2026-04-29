package com.lwg.challenge

import com.lwg.challenge.infra.auth.UserJpaRepository
import com.lwg.challenge.infra.kakao.KakaoOAuthClient
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * Smoke test: Spring context 로드만 검증.
 *
 * 로컬 Postgres / Redis가 없어도 통과하도록:
 * - DB / JPA / Flyway / Redis auto-configuration 제외.
 * - `UserJpaRepository` 는 `@MockitoBean` 으로 가짜 빈 등록 (DataSource 없이 실 생성 불가).
 *
 * 실제 카카오 로그인 흐름 + DB 상호작용은 `AuthKakaoIntegrationTest` 에서 Testcontainers로 검증.
 */
@SpringBootTest(
    properties = [
        "jwt.secret=test-only-secret-not-used-anywhere-else-minimum-32-bytes-abcdef",
        // 카카오 키들은 yml에서 default 없이 env var 강제 → smoke test에선 더미 값 주입
        "kakao.rest-api-key=test-rest-api-key",
        "kakao.redirect-uri=http://localhost/oauth/kakao/callback",
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    ]
)
class ChallengeServerApplicationTests {

    // JPA가 제외됐으므로 Repository 빈을 직접 mock으로 공급. AuthService가 주입받는 의존성 충족용.
    @MockitoBean
    lateinit var userRepository: UserJpaRepository

    // KakaoOAuthClient는 실제로는 @Component라 생성되지만, 테스트 환경에서 네트워크 호출되면 안 되므로 mock.
    @MockitoBean
    lateinit var kakaoOAuthClient: KakaoOAuthClient

    @Test
    fun contextLoads() {
        // Context 로드 자체가 성공하면 pass.
    }
}
