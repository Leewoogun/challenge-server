package com.lwg.challenge.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.core.auth.JwtTokenProvider
import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.notification.NotificationType
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.infra.entity.auth.UserEntity
import com.lwg.challenge.infra.entity.challenge.ChallengeEntity
import com.lwg.challenge.infra.entity.notification.NotificationEntity
import com.lwg.challenge.infra.jpa.auth.UserJpaRepository
import com.lwg.challenge.infra.jpa.challenge.ChallengeJpaRepository
import com.lwg.challenge.infra.jpa.notification.NotificationJpaRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 알림 `referencePending` 과 홈 '받은 도전장' 이 **같은 답을 내는지** 실제 DB 로 확인한다.
 *
 * ## 🔴 무엇을 막는 테스트인가
 *
 * 두 화면은 *"이 사용자가 아직 응답을 기다리는 신청"* 이라는 **같은 도메인 규칙**을 서로 다른
 * 쿼리로 구현한다 — 홈은 `ChallengeJpaRepository.findReceivedPending`(native), 알림은
 * `findPendingReceivedIdsIn`(JPQL). 조건이 갈라지면 **알림은 탭되는데 홈엔 그 카드가 없다**
 * (또는 그 반대). 두 쿼리 다 성공하고 응답도 200 이라 **예외도 로그도 남지 않는다.**
 *
 * ## 🔴 세 단언을 **한 테스트 안에서** 한다 — 이게 설계다
 *
 * 홈 검증과 알림 검증을 별개 테스트로 쪼개면 **각각 통과하면서 서로 어긋날 수 있다.** 그래서
 * 같은 시드·같은 순간에 두 엔드포인트를 다 부르고, 마지막에 **대상 집합이 일치하는지**를 본다:
 *
 * 1. 홈 '받은 도전장' 에 **살아 있는 것만** 온다
 * 2. 알림의 `referencePending` 이 **살아 있는 쪽 `true` / 만료된 쪽 `false`**
 * 3. 🔴 **두 결과의 대상 집합이 같다** — *"홈의 challengeId 집합"* == *"referencePending=true 인
 *    알림의 referenceId 집합"*. **3번이 이 테스트의 본체다.** 1·2 는 3번이 «둘 다 비어서» 통과하는
 *    자명한 방식으로 성립하지 않게 붙잡는 역할이다.
 *
 * ## 시드가 노리는 것 — lazy expiry
 *
 * `PENDING` + 마감 미래 1건, `PENDING` + 마감 과거 1건. 🔴 **후자가 핵심이다** — 마감이 지나도
 * status 는 `EXPIRED` 로 바뀌지 않고 DB 에 `PENDING` 인 채로 남는다(전이는 배치가 한다). 즉
 * `status = 'PENDING'` 만 보는 구현은 이 row 를 **"대기 중"으로 집는다.** 두 쿼리 중 어느 쪽에서든
 * `deadline > :now` 가 빠지면 그 순간 여기서 깨진다.
 *
 * ## ⚠️ Docker 없으면 skip 된다
 *
 * 기존 통합 테스트들과 같은 처지다 — 컨테이너 런타임이 없는 환경에서 늘어나는 skip 수는
 * 회귀가 아니다. **항상 도는 쪽은 `ChallengeReceivedPredicateBindingTest`**(쿼리 문자열 구조
 * 단언)이고, 그쪽이 못 하는 *"의미가 같다"* 를 이 테스트가 맡는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIf(
    value = "com.lwg.challenge.integration.NotificationReferencePendingIntegrationTest#isDockerAvailable",
    disabledReason = "Docker 데몬이 필요합니다 (Testcontainers Postgres). Docker Desktop 실행 후 재시도하세요.",
)
class NotificationReferencePendingIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var userJpaRepository: UserJpaRepository

    @Autowired
    lateinit var challengeJpaRepository: ChallengeJpaRepository

    @Autowired
    lateinit var notificationJpaRepository: NotificationJpaRepository

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    companion object {

        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("challenge_test")
            .withUsername("test")
            .withPassword("test")

        @JvmStatic
        fun isDockerAvailable(): Boolean =
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

        @BeforeAll
        @JvmStatic
        fun startInfra() {
            if (!postgres.isRunning) postgres.start()
        }

        @AfterAll
        @JvmStatic
        fun stopInfra() {
            if (postgres.isRunning) postgres.stop()
        }

        @DynamicPropertySource
        @JvmStatic
        fun registerProps(registry: DynamicPropertyRegistry) {
            if (!postgres.isRunning) postgres.start()
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.flyway.url") { postgres.jdbcUrl }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
            registry.add("jwt.secret") { "integration-test-jwt-secret-minimum-32-bytes-xxxxxx" }
            registry.add("kakao.api-base-url") { "http://localhost:0" }
        }
    }

    @AfterEach
    fun resetState() {
        // FK 의존 역순으로 정리.
        notificationJpaRepository.deleteAll()
        challengeJpaRepository.deleteAll()
        userJpaRepository.deleteAll()
    }

    @Test
    fun `알림 referencePending 과 홈 받은 도전장이 같은 집합을 가리킨다 - 갈라지면 알림은 탭되는데 홈엔 없다`() {
        val challenger = newUser(kakaoId = 1L, nickname = "도전자")
        val me = newUser(kakaoId = 2L, nickname = "수신자")

        // 🔴 두 건 다 `status = PENDING` 이다. 갈리는 축은 **deadline 하나뿐**이라
        //    `deadline > now` 를 안 보는 구현은 둘을 구분하지 못한다.
        // ⚠️ challenge_date 를 다르게 둔 것은 시나리오가 아니라 제약 때문이다 —
        //    V5 의 uq_challenges_active_pair_date 가 같은 쌍·같은 날짜의 PENDING 2건을 막는다.
        val alive = seedChallenge(
            challengerId = challenger.id!!,
            opponentId = me.id!!,
            challengeDate = KstTime.today(),
            deadline = KstTime.now().plusDays(1),
        )
        val expired = seedChallenge(
            challengerId = challenger.id!!,
            opponentId = me.id!!,
            challengeDate = KstTime.today().minusDays(1),
            deadline = KstTime.now().minusDays(1),
        )

        val aliveNotification = seedChallengeRequestNotification(userId = me.id!!, challengeId = alive.id!!)
        val expiredNotification = seedChallengeRequestNotification(userId = me.id!!, challengeId = expired.id!!)

        // ─── 1. 홈 '받은 도전장' — 살아 있는 것만 ──────────────────

        val homeChallengeIds: Set<Long> = fetchReceivedChallengeIds(me.id!!)

        assertEquals(
            setOf(alive.id!!),
            homeChallengeIds,
            "홈 '받은 도전장'에는 마감 전 PENDING 만 와야 한다. 만료된 ${expired.id} 가 섞였다면 " +
                "findReceivedPending 의 `deadline > :now` 가 사라진 것이다 (lazy expiry 로 status 는 PENDING 그대로다)",
        )

        // ─── 2. 알림 referencePending — 살아 있는 쪽 true, 만료된 쪽 false ───

        val pendingByNotificationId: Map<Long, Boolean?> = fetchReferencePendingByNotificationId(me.id!!)

        assertEquals(
            true,
            pendingByNotificationId[aliveNotification.id],
            "마감 전 PENDING 을 가리키는 CHALLENGE_REQUEST 알림은 referencePending=true 여야 한다. " +
                "null 이면 타입 분기가 깨진 것이고(CHALLENGE_REQUEST 는 non-null 보장), " +
                "false 면 findPendingReceivedIdsIn 이 살아 있는 신청을 못 집은 것이다",
        )
        assertEquals(
            false,
            pendingByNotificationId[expiredNotification.id],
            "마감이 지난 신청을 가리키는 알림은 referencePending=false 여야 한다. true 라면 " +
                "findPendingReceivedIdsIn 의 `deadline > :now` 가 빠져 만료된 신청을 «대기 중» 으로 내보내고 있다 " +
                "— 앱은 탭을 허용하고 사용자는 빈 '받은 도전장' 화면에 도착한다",
        )

        // ─── 3. 🔴 두 결과의 대상 집합이 일치한다 (본체) ──────────

        val tappableReferenceIds: Set<Long> = fetchPendingTrueReferenceIds(me.id!!)

        // 이 비교가 «양쪽 다 공집합» 으로 자명하게 통과하는 일은 없다 —
        // 1번이 홈 집합을 정확히 {alive.id} 로 이미 고정했다.
        assertEquals(
            homeChallengeIds,
            tappableReferenceIds,
            """
            🔴 홈 '받은 도전장'과 알림 referencePending 이 서로 다른 대상을 가리킨다.
               홈의 challengeId 집합                    : $homeChallengeIds
               referencePending=true 인 referenceId 집합 : $tappableReferenceIds

            두 조회는 같은 도메인 규칙의 두 구현이다 —
            findReceivedPending(native) / findPendingReceivedIdsIn(JPQL).
            갈라지면 «알림은 탭되는데 홈엔 그 카드가 없다» 또는 그 반대가 되고,
            둘 다 200 을 반환하므로 예외도 로그도 남지 않는다.

            두 쿼리의 WHERE 절 3개(opponent / status='PENDING' / deadline > :now)를 나란히 놓고 비교해라.
            문자열 수준의 대조는 ChallengeReceivedPredicateBindingTest 가 한다.
            """.trimIndent(),
        )
    }

    // ─── Fixtures ────────────────────────────────────────────────

    private fun newUser(kakaoId: Long, nickname: String): UserEntity {
        val now = KstTime.now()
        return userJpaRepository.save(
            UserEntity(
                id = null,
                kakaoId = kakaoId,
                nickname = nickname,
                profileImageUrl = null,
                phoneNumber = null,
                phoneVerified = false,
                fcmToken = null,
                status = UserStatus.ACTIVE.name,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun seedChallenge(
        challengerId: Long,
        opponentId: Long,
        challengeDate: LocalDate,
        deadline: LocalDateTime,
    ): ChallengeEntity = challengeJpaRepository.save(
        ChallengeEntity(
            id = null,
            challengerId = challengerId,
            opponentId = opponentId,
            challengerMission = "시드 미션",
            // PENDING 이라 상대 미션은 아직 없다 (V5 nullable).
            opponentMission = null,
            betContent = "시드 내기",
            challengeDate = challengeDate,
            deadline = deadline,
            status = ChallengeStatus.PENDING.name,
            result = null,
            createdAt = KstTime.now(),
            completedAt = null,
        ),
    )

    private fun seedChallengeRequestNotification(userId: Long, challengeId: Long): NotificationEntity =
        notificationJpaRepository.save(
            NotificationEntity(
                id = null,
                userId = userId,
                type = NotificationType.CHALLENGE_REQUEST.name,
                title = "도전장이 도착했어요",
                body = "시드 알림",
                referenceId = challengeId,
                isRead = false,
                createdAt = KstTime.now(),
            ),
        )

    // ─── API 호출 ────────────────────────────────────────────────

    /** `GET /api/v1/challenges/received` 의 challengeId 집합. */
    private fun fetchReceivedChallengeIds(me: Long): Set<Long> =
        get("/api/v1/challenges/received", me)
            .path("data").path("challenges")
            .map { it.path("challengeId").asLong() }
            .toSet()

    /**
     * `GET /api/v1/notifications` 의 `notificationId → referencePending`.
     *
     * 🔴 `false` 와 `null` 을 접지 않는다 — `null` 은 «비탭» 이 아니라 «이 타입엔 개념이 없다» 라서
     * 둘을 같게 읽으면 이 테스트가 검증하려는 구분 자체가 사라진다 (`NotificationDto` KDoc).
     */
    private fun fetchReferencePendingByNotificationId(me: Long): Map<Long, Boolean?> =
        get("/api/v1/notifications", me)
            .path("data").path("notifications")
            .associate { node ->
                val pending = node.path("referencePending")
                node.path("notificationId").asLong() to if (pending.isNull) null else pending.asBoolean()
            }

    /** 알림 목록에서 **`referencePending == true` 인 것만**의 `referenceId` 집합. */
    private fun fetchPendingTrueReferenceIds(me: Long): Set<Long> =
        get("/api/v1/notifications", me)
            .path("data").path("notifications")
            .filter { it.path("referencePending").asBoolean(false) }
            .map { it.path("referenceId").asLong() }
            .toSet()

    private fun get(path: String, me: Long): JsonNode {
        val body = mockMvc.perform(
            MockMvcRequestBuilders.get(path)
                .header("Authorization", "Bearer ${accessTokenFor(me)}"),
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val root = objectMapper.readTree(body)
        assertTrue(
            !root.path("error").asBoolean(true),
            "$path 가 에러 응답을 냈다. 이 테스트는 두 정상 응답의 대조가 목적이라 여기서 멈춘다. body=$body",
        )
        return root
    }

    private fun accessTokenFor(userId: Long): String = jwtTokenProvider.generateAccessToken(userId)
}
