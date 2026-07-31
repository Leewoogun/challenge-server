package com.lwg.challenge.integration

import com.lwg.challenge.core.auth.JwtTokenProvider
import com.lwg.challenge.core.challenge.KstDeadlineCalculator
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.friend.FriendshipStatus
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.domain.verification.VerificationStatus
import com.lwg.challenge.infra.entity.auth.UserEntity
import com.lwg.challenge.infra.entity.challenge.ChallengeEntity
import com.lwg.challenge.infra.entity.friend.FriendshipEntity
import com.lwg.challenge.infra.jpa.auth.UserJpaRepository
import com.lwg.challenge.infra.jpa.challenge.ChallengeJpaRepository
import com.lwg.challenge.infra.jpa.friend.FriendshipJpaRepository
import com.lwg.challenge.infra.jpa.verification.VerificationJpaRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * challenge-create end-to-end 통합 테스트 (T-B4).
 *
 * Testcontainers Postgres + Flyway V1→V3→V4→**V5** → 실제 스키마.
 * **V5 마이그레이션(opponent_mission nullable + 부분 유니크 인덱스)이 실제로 적용되는지가
 * 이 테스트로만 검증된다** — 슬라이스/단위 테스트는 DB 를 안 탄다.
 *
 * 커버 시나리오:
 *  1. 신청 → PENDING + opponent_mission NULL (V5 nullable 검증) + KST 자정 환산
 *  2. 받은 도전장 목록 — 방향/상태/마감/JOIN/정렬
 *  3. 수락 → IN_PROGRESS + verifications 2건 + **read-after-write** (즉시 /active 반영, /received 제거)
 *  4. 거절 → REJECTED + 목록에서 제거 + 같은 날 재도전 가능
 *  5. 취소 → 물리 삭제
 *  6. 중복 — 양방향 차단 + 날짜 다르면 허용
 *  7. **DB 부분 유니크 인덱스**가 애플리케이션 검사를 우회한 INSERT 도 막는지 (V5 2번 블록 검증)
 *  8. 친구 아님 / 본인 / 권한 위반
 *  9. 미인증 401
 *
 * Docker 미가용 환경에서는 전체 skip (기존 통합 테스트들과 동일 패턴).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIf(
    value = "com.lwg.challenge.integration.ChallengeCreateIntegrationTest#isDockerAvailable",
    disabledReason = "Docker 데몬이 필요합니다 (Testcontainers Postgres). Docker Desktop 실행 후 재시도하세요.",
)
class ChallengeCreateIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userJpaRepository: UserJpaRepository

    @Autowired
    lateinit var challengeJpaRepository: ChallengeJpaRepository

    @Autowired
    lateinit var friendshipJpaRepository: FriendshipJpaRepository

    @Autowired
    lateinit var verificationJpaRepository: VerificationJpaRepository

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
        verificationJpaRepository.deleteAll()
        challengeJpaRepository.deleteAll()
        friendshipJpaRepository.deleteAll()
        userJpaRepository.deleteAll()
    }

    // ─── 1. 신청 ─────────────────────────────────────────────────

    @Test
    fun `신청하면 PENDING 이고 opponent_mission 은 NULL 이다 (V5 nullable 검증)`() {
        val (me, friend) = twoFriends()

        val result = createChallenge(me.id!!, friend.id!!, "운동 1시간", "커피 사기", "TODAY")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andReturn()

        val row = challengeJpaRepository.findAll().single()
        assertEquals(ChallengeStatus.PENDING.name, row.status)
        // V1 의 NOT NULL 이 남아 있었다면 INSERT 자체가 실패했을 것이다.
        assertNull(row.opponentMission, "PENDING 챌린지의 opponent_mission 은 NULL 이어야 한다")
        assertEquals("운동 1시간", row.challengerMission)
        assertEquals(me.id, row.challengerId)
        assertEquals(friend.id, row.opponentId)

        // KST 환산 — challengeDate 는 KST 오늘, deadline 은 그 날의 KST 자정을 UTC 로 옮긴 값.
        val expectedDate = Instant.now().atZone(KstDeadlineCalculator.KST).toLocalDate()
        assertEquals(expectedDate, row.challengeDate)
        assertEquals(KstDeadlineCalculator.endOfKstDayInUtc(expectedDate), row.deadline)

        // 응답 JSON 의 날짜가 평문 ISO date 인지 (모바일이 String 으로 받는다).
        val body = result.response.contentAsString
        assertTrue(
            body.contains("\"challengeDate\":\"$expectedDate\""),
            "challengeDate 는 평문 ISO date 문자열이어야 한다. actual body=$body",
        )
    }

    @Test
    fun `TOMORROW 로 신청하면 challenge_date 가 내일이다`() {
        val (me, friend) = twoFriends()

        createChallenge(me.id!!, friend.id!!, "운동", "커피", "TOMORROW")
            .andExpect(status().isOk)

        val row = challengeJpaRepository.findAll().single()
        val kstTomorrow = Instant.now().atZone(KstDeadlineCalculator.KST).toLocalDate().plusDays(1)
        assertEquals(kstTomorrow, row.challengeDate)
        assertEquals(KstDeadlineCalculator.endOfKstDayInUtc(kstTomorrow), row.deadline)
    }

    @Test
    fun `친구가 아니면 신청이 거부된다`() {
        val me = newUser(1L, "나")
        val stranger = newUser(2L, "남남") // friendship row 없음

        createChallenge(me.id!!, stranger.id!!, "운동", "커피", "TODAY")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("친구에게만 챌린지를 걸 수 있어요"))

        assertTrue(challengeJpaRepository.findAll().isEmpty())
    }

    @Test
    fun `본인에게는 신청할 수 없다`() {
        val me = newUser(1L, "나")

        createChallenge(me.id!!, me.id!!, "운동", "커피", "TODAY")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("자기 자신에게는 챌린지를 걸 수 없어요"))
    }

    // ─── 2. 받은 도전장 목록 ──────────────────────────────────────

    @Test
    fun `받은 도전장은 내가 opponent 인 PENDING 만 나오고 challenger 정보가 JOIN 된다`() {
        val (me, friend) = twoFriends()
        userJpaRepository.save(friend.apply { profileImageUrl = "https://cdn/u/2.jpg" })

        // friend → me (받은 것)
        createChallenge(friend.id!!, me.id!!, "운동 1시간", "커피 사기", "TODAY").andExpect(status().isOk)
        // me → friend (보낸 것, 날짜가 겹치지 않게 내일로)
        createChallenge(me.id!!, friend.id!!, "독서", "밥 사기", "TOMORROW").andExpect(status().isOk)

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/challenges/received")
                .header("Authorization", "Bearer ${accessTokenFor(me.id!!)}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.challenges.length()").value(1))
            .andExpect(jsonPath("$.data.challenges[0].challengerId").value(friend.id!!))
            .andExpect(jsonPath("$.data.challenges[0].challengerNickname").value("민수"))
            .andExpect(jsonPath("$.data.challenges[0].challengerProfileImageUrl").value("https://cdn/u/2.jpg"))
            .andExpect(jsonPath("$.data.challenges[0].challengerMission").value("운동 1시간"))
            .andExpect(jsonPath("$.data.challenges[0].betContent").value("커피 사기"))
    }

    @Test
    fun `마감이 지난 PENDING 은 목록에서 빠지지만 DB status 는 그대로다 (lazy expiry)`() {
        val (me, friend) = twoFriends()
        // 마감이 이미 지난 PENDING 을 직접 심는다.
        seedChallenge(
            challengerId = friend.id!!,
            opponentId = me.id!!,
            status = ChallengeStatus.PENDING,
            challengeDate = LocalDate.now().minusDays(2),
            deadline = LocalDateTime.now(ZoneOffset.UTC).minusHours(1),
        )

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/challenges/received")
                .header("Authorization", "Bearer ${accessTokenFor(me.id!!)}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.challenges.length()").value(0))

        // 응답에서만 빠지고 DB 는 PENDING 그대로 — EXPIRED 전이 배치는 후속 작업.
        assertEquals(ChallengeStatus.PENDING.name, challengeJpaRepository.findAll().single().status)
    }

    @Test
    fun `받은 도전장이 없으면 빈 배열`() {
        val (me, _) = twoFriends()

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/challenges/received")
                .header("Authorization", "Bearer ${accessTokenFor(me.id!!)}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.challenges").isArray)
            .andExpect(jsonPath("$.data.challenges.length()").value(0))
    }

    // ─── 3. 수락 (+ read-after-write) ────────────────────────────

    @Test
    fun `수락하면 IN_PROGRESS + verifications 2건이 같은 트랜잭션에서 생성된다`() {
        val (me, friend) = twoFriends()
        val challengeId = createAndExtractId(friend.id!!, me.id!!, "운동 1시간", "커피 사기", "TODAY")

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/challenges/$challengeId/accept")
                .header("Authorization", "Bearer ${accessTokenFor(me.id!!)}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"myMission":"책 30페이지 읽기"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))

        val row = challengeJpaRepository.findById(challengeId).orElseThrow()
        assertEquals(ChallengeStatus.IN_PROGRESS.name, row.status)
        assertEquals("책 30페이지 읽기", row.opponentMission)

        val verifications = verificationJpaRepository.findAllByChallengeIdIn(listOf(challengeId))
        assertEquals(2, verifications.size)
        assertEquals(setOf(me.id, friend.id), verifications.map { it.userId }.toSet())
        assertTrue(verifications.all { it.status == VerificationStatus.PENDING.name })
        assertTrue(verifications.all { it.photoUrl == null && it.verifiedAt == null })
    }

    @Test
    fun `수락 직후 곧바로 조회하면 active 에 잡히고 received 에서는 사라진다 (read-after-write)`() {
        val (me, friend) = twoFriends()
        val challengeId = createAndExtractId(friend.id!!, me.id!!, "운동 1시간", "커피 사기", "TODAY")
        val token = accessTokenFor(me.id!!)

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/challenges/$challengeId/accept")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"myMission":"책 30페이지 읽기"}"""),
        ).andExpect(status().isOk)

        // 지연/재시도 없이 즉시 두 목록을 호출한다 — 모바일이 실제로 하는 순서 그대로.
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/challenges/active").header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.activeChallenges.length()").value(1))
            .andExpect(jsonPath("$.data.activeChallenges[0].challengeId").value(challengeId))
            // 내가 opponent 이므로 myMission 은 내가 방금 입력한 값.
            .andExpect(jsonPath("$.data.activeChallenges[0].myMission").value("책 30페이지 읽기"))
            .andExpect(jsonPath("$.data.activeChallenges[0].opponentMission").value("운동 1시간"))
            .andExpect(jsonPath("$.data.activeChallenges[0].opponentNickname").value("민수"))
            .andExpect(jsonPath("$.data.activeChallenges[0].myVerificationStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.activeChallenges[0].opponentVerificationStatus").value("PENDING"))

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/challenges/received").header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.challenges.length()").value(0))
    }

    @Test
    fun `이미 수락된 챌린지를 다시 수락하면 705 로 거부된다`() {
        val (me, friend) = twoFriends()
        val challengeId = createAndExtractId(friend.id!!, me.id!!, "운동", "커피", "TODAY")
        accept(me.id!!, challengeId, "책").andExpect(status().isOk)

        accept(me.id!!, challengeId, "다시")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.ONE_BUTTON_DIALOG_ERROR))
            .andExpect(jsonPath("$.message").value("이미 처리된 챌린지예요"))

        // 재수락이 막혔으니 verifications 도 2건 그대로.
        assertEquals(2, verificationJpaRepository.findAllByChallengeIdIn(listOf(challengeId)).size)
    }

    @Test
    fun `당사자가 아닌 사용자는 수락할 수 없다`() {
        val (me, friend) = twoFriends()
        val stranger = newUser(3L, "남남")
        val challengeId = createAndExtractId(friend.id!!, me.id!!, "운동", "커피", "TODAY")

        accept(stranger.id!!, challengeId, "책")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("내가 받은 도전장이 아니에요"))

        assertEquals(ChallengeStatus.PENDING.name, challengeJpaRepository.findById(challengeId).orElseThrow().status)
    }

    @Test
    fun `마감이 지난 챌린지는 수락할 수 없다`() {
        val (me, friend) = twoFriends()
        val seeded = seedChallenge(
            challengerId = friend.id!!,
            opponentId = me.id!!,
            status = ChallengeStatus.PENDING,
            challengeDate = LocalDate.now().minusDays(2),
            deadline = LocalDateTime.now(ZoneOffset.UTC).minusHours(1),
        )

        accept(me.id!!, seeded.id!!, "책")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.ONE_BUTTON_DIALOG_ERROR))
            .andExpect(jsonPath("$.message").value("마감이 지난 챌린지예요"))

        assertTrue(verificationJpaRepository.findAll().isEmpty())
    }

    // ─── 4. 거절 ─────────────────────────────────────────────────

    @Test
    fun `거절하면 REJECTED 로 남고 목록에서 사라지며 같은 날 재도전할 수 있다`() {
        val (me, friend) = twoFriends()
        val challengeId = createAndExtractId(friend.id!!, me.id!!, "운동", "커피", "TODAY")

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/challenges/$challengeId/reject")
                .header("Authorization", "Bearer ${accessTokenFor(me.id!!)}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("REJECTED"))

        // row 는 보존된다 (물리 삭제 아님).
        assertEquals(ChallengeStatus.REJECTED.name, challengeJpaRepository.findById(challengeId).orElseThrow().status)

        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/challenges/received")
                .header("Authorization", "Bearer ${accessTokenFor(me.id!!)}"),
        ).andExpect(jsonPath("$.data.challenges.length()").value(0))

        // REJECTED 는 중복 검사 대상이 아니므로 같은 날 다시 걸 수 있다.
        createChallenge(me.id!!, friend.id!!, "독서", "밥 사기", "TODAY")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
    }

    // ─── 5. 취소 ─────────────────────────────────────────────────

    @Test
    fun `챌린저가 취소하면 물리 삭제된다`() {
        val (me, friend) = twoFriends()
        val challengeId = createAndExtractId(me.id!!, friend.id!!, "운동", "커피", "TODAY")

        mockMvc.perform(
            MockMvcRequestBuilders.delete("/api/v1/challenges/$challengeId")
                .header("Authorization", "Bearer ${accessTokenFor(me.id!!)}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.challengeId").value(challengeId))

        assertTrue(challengeJpaRepository.findAll().isEmpty())
    }

    @Test
    fun `받은 사람은 취소할 수 없다`() {
        val (me, friend) = twoFriends()
        val challengeId = createAndExtractId(me.id!!, friend.id!!, "운동", "커피", "TODAY")

        mockMvc.perform(
            MockMvcRequestBuilders.delete("/api/v1/challenges/$challengeId")
                .header("Authorization", "Bearer ${accessTokenFor(friend.id!!)}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("내가 보낸 도전장이 아니에요"))

        assertEquals(1, challengeJpaRepository.findAll().size)
    }

    // ─── 6. 중복 (양방향) ────────────────────────────────────────

    @Test
    fun `같은 날 내가 이미 건 챌린지가 있으면 중복이다`() {
        val (me, friend) = twoFriends()
        createChallenge(me.id!!, friend.id!!, "운동", "커피", "TODAY").andExpect(status().isOk)

        createChallenge(me.id!!, friend.id!!, "독서", "밥 사기", "TODAY")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.message").value("오늘은 이 친구와 이미 챌린지가 있어요"))

        assertEquals(1, challengeJpaRepository.findAll().size)
    }

    @Test
    fun `상대가 나에게 건 PENDING 도 중복이고 받은 도전장으로 안내한다`() {
        val (me, friend) = twoFriends()
        createChallenge(friend.id!!, me.id!!, "운동", "커피", "TODAY").andExpect(status().isOk)

        createChallenge(me.id!!, friend.id!!, "독서", "밥 사기", "TODAY")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.message").value("이 친구가 이미 도전장을 보냈어요. 받은 도전장을 확인해보세요"))

        assertEquals(1, challengeJpaRepository.findAll().size)
    }

    @Test
    fun `날짜가 다르면 같은 친구에게도 걸 수 있다`() {
        val (me, friend) = twoFriends()
        createChallenge(me.id!!, friend.id!!, "운동", "커피", "TODAY").andExpect(status().isOk)

        createChallenge(me.id!!, friend.id!!, "독서", "밥 사기", "TOMORROW")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))

        assertEquals(2, challengeJpaRepository.findAll().size)
    }

    // ─── 7. DB 제약 (V5 부분 유니크 인덱스) ──────────────────────

    @Test
    fun `부분 유니크 인덱스가 애플리케이션 검사를 우회한 중복 INSERT 도 막는다`() {
        val (me, friend) = twoFriends()
        val date = LocalDate.now()
        seedChallenge(me.id!!, friend.id!!, ChallengeStatus.PENDING, date, endOfDay(date))

        // 서비스 로직을 거치지 않고 JPA 로 직접 반대 방향 중복을 밀어넣는다 —
        // 동시 요청 race 가 사전 검사를 둘 다 통과한 상황과 같다.
        val ex = runCatching {
            challengeJpaRepository.saveAndFlush(
                ChallengeEntity(
                    id = null,
                    challengerId = friend.id!!,
                    opponentId = me.id!!,
                    challengerMission = "우회 시도",
                    opponentMission = null,
                    betContent = "우회",
                    challengeDate = date,
                    deadline = endOfDay(date),
                    status = ChallengeStatus.PENDING.name,
                    result = null,
                    createdAt = LocalDateTime.now(ZoneOffset.UTC),
                    completedAt = null,
                ),
            )
        }.exceptionOrNull()

        assertNotNull(ex, "uq_challenges_active_pair_date 가 방향 무관 중복을 막아야 한다")
        assertEquals(1, challengeJpaRepository.findAll().size)
    }

    @Test
    fun `부분 유니크 인덱스는 REJECTED 를 대상으로 하지 않는다`() {
        val (me, friend) = twoFriends()
        val date = LocalDate.now()
        seedChallenge(me.id!!, friend.id!!, ChallengeStatus.REJECTED, date, endOfDay(date))

        // REJECTED 는 인덱스의 WHERE 절 밖이라 같은 쌍·같은 날짜로 여러 건 존재할 수 있어야 한다.
        seedChallenge(friend.id!!, me.id!!, ChallengeStatus.REJECTED, date, endOfDay(date))

        assertEquals(2, challengeJpaRepository.findAll().size)
    }

    // ─── 8. 미인증 ───────────────────────────────────────────────

    @Test
    fun `토큰이 없으면 code 401`() {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/challenges/received"))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/challenges")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"opponentId":1,"myMission":"운동","betContent":"커피","deadlineType":"TODAY"}"""),
        ).andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))
    }

    // ─── helpers ─────────────────────────────────────────────────

    private fun twoFriends(): Pair<UserEntity, UserEntity> {
        val me = newUser(1L, "나")
        val friend = newUser(2L, "민수")
        friendshipJpaRepository.save(
            FriendshipEntity(
                id = null,
                requesterId = me.id!!,
                receiverId = friend.id!!,
                status = FriendshipStatus.ACCEPTED.name,
                createdAt = LocalDateTime.now(ZoneOffset.UTC),
                acceptedAt = LocalDateTime.now(ZoneOffset.UTC),
            ),
        )
        return me to friend
    }

    private fun newUser(kakaoId: Long, nickname: String): UserEntity {
        val now = LocalDateTime.now(ZoneOffset.UTC)
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
        status: ChallengeStatus,
        challengeDate: LocalDate,
        deadline: LocalDateTime,
    ): ChallengeEntity = challengeJpaRepository.save(
        ChallengeEntity(
            id = null,
            challengerId = challengerId,
            opponentId = opponentId,
            challengerMission = "시드 미션",
            opponentMission = if (status == ChallengeStatus.IN_PROGRESS) "시드 상대 미션" else null,
            betContent = "시드 내기",
            challengeDate = challengeDate,
            deadline = deadline,
            status = status.name,
            result = null,
            createdAt = LocalDateTime.now(ZoneOffset.UTC),
            completedAt = null,
        ),
    )

    private fun endOfDay(date: LocalDate): LocalDateTime =
        KstDeadlineCalculator.endOfKstDayInUtc(date)

    private fun createChallenge(
        me: Long,
        opponentId: Long,
        mission: String,
        bet: String,
        deadlineType: String,
    ): ResultActions = mockMvc.perform(
        MockMvcRequestBuilders.post("/api/v1/challenges")
            .header("Authorization", "Bearer ${accessTokenFor(me)}")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"opponentId":$opponentId,"myMission":"$mission","betContent":"$bet","deadlineType":"$deadlineType"}
                """.trimIndent(),
            ),
    )

    /** 신청 후 생성된 challengeId 를 DB 에서 되찾는다 (응답 파싱 대신 — 어떤 row 인지 확실히 하기 위해). */
    private fun createAndExtractId(
        challengerId: Long,
        opponentId: Long,
        mission: String,
        bet: String,
        deadlineType: String,
    ): Long {
        createChallenge(challengerId, opponentId, mission, bet, deadlineType).andExpect(status().isOk)
        return challengeJpaRepository.findAll()
            .single { it.challengerId == challengerId && it.opponentId == opponentId && it.challengerMission == mission }
            .id!!
    }

    private fun accept(me: Long, challengeId: Long, mission: String): ResultActions = mockMvc.perform(
        MockMvcRequestBuilders.post("/api/v1/challenges/$challengeId/accept")
            .header("Authorization", "Bearer ${accessTokenFor(me)}")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"myMission":"$mission"}"""),
    )

    private fun accessTokenFor(userId: Long): String = jwtTokenProvider.generateAccessToken(userId)
}
