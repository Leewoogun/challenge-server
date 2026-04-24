package com.lwg.challenge

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// Sprint 0 smoke test: Spring context 로드 검증.
// 로컬 Postgres / Redis가 없어도 통과하도록 DB / Flyway / Redis auto-configuration을 제외한다.
// 실제 DB 포함 통합 테스트는 Sprint 1부터 Testcontainers로 도입 예정.
@SpringBootTest(
    properties = [
        // JwtTokenProvider 빈 생성에 필요 (운영은 JWT_SECRET env 주입 강제)
        "jwt.secret=test-only-secret-not-used-anywhere-else-minimum-32-bytes-abcdef",
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    ]
)
class ChallengeServerApplicationTests {

    @Test
    fun contextLoads() {
        // Context 로드 자체가 성공하면 pass.
    }
}
