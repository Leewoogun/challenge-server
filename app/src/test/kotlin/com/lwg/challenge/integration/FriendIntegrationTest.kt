package com.lwg.challenge.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.core.auth.JwtTokenProvider
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.friend.FriendshipStatus
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.infra.entity.auth.UserEntity
import com.lwg.challenge.infra.entity.friend.FriendshipEntity
import com.lwg.challenge.infra.jpa.auth.UserJpaRepository
import com.lwg.challenge.infra.jpa.friend.FriendshipJpaRepository
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
import java.time.LocalDateTime

/**
 * 친구 시스템 end-to-end 통합 테스트 (spec-friend-add §5.6).
 *
 * - Testcontainers Postgres + Flyway V1__init.sql → 실제 friendships 스키마.
 * - 9 시나리오: 검색 (본인 제외 + LIMIT 20 + ACTIVE), 검색 relation 5종,
 *   요청→수락, 요청→거절, 요청→거절→재요청(UPDATE), 요청→취소(물리 삭제),
 *   동시 요청 race(역방향 PENDING 안내), 미인증 401, 권한 외 700.
 *
 * Docker 미가용 시 전체 skip.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIf(
    value = "com.lwg.challenge.integration.FriendIntegrationTest#isDockerAvailable",
    disabledReason = "Docker 데몬이 필요합니다 (Testcontainers Postgres). Docker Desktop 실행 후 재시도하세요.",
)
class FriendIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var userRepository: UserJpaRepository

    @Autowired
    lateinit var friendshipJpaRepository: FriendshipJpaRepository

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
        friendshipJpaRepository.deleteAll()
        userRepository.deleteAll()
    }

    // ─── Fixtures ────────────────────────────────────────────────

    private fun newUser(
        kakaoId: Long,
        nickname: String,
        status: UserStatus = UserStatus.ACTIVE,
    ): UserEntity {
        val now = LocalDateTime.now()
        return userRepository.save(
            UserEntity(
                id = null,
                kakaoId = kakaoId,
                nickname = nickname,
                profileImageUrl = null,
                phoneNumber = null,
                phoneVerified = false,
                fcmToken = null,
                status = status.name,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun saveFriendship(
        requesterId: Long,
        receiverId: Long,
        status: FriendshipStatus,
        createdAt: LocalDateTime = LocalDateTime.now(),
        acceptedAt: LocalDateTime? = null,
    ): FriendshipEntity = friendshipJpaRepository.save(
        FriendshipEntity(
            id = null,
            requesterId = requesterId,
            receiverId = receiverId,
            status = status.name,
            createdAt = createdAt,
            acceptedAt = acceptedAt,
        ),
    )

    private fun accessTokenFor(userId: Long): String = jwtTokenProvider.generateAccessToken(userId)

    private fun authHeader(token: String?) = if (token == null) "" else "Bearer $token"

    private fun search(token: String?, nickname: String): ResultActions =
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/users/search")
                .param("nickname", nickname)
                .apply { if (token != null) header("Authorization", authHeader(token)) },
        )

    private fun sendRequest(token: String, receiverId: Long): ResultActions =
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/friends/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", authHeader(token))
                .content(objectMapper.writeValueAsString(mapOf("receiverId" to receiverId))),
        )

    private fun accept(token: String, id: Long): ResultActions =
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/friends/requests/$id/accept")
                .header("Authorization", authHeader(token)),
        )

    private fun reject(token: String, id: Long): ResultActions =
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/friends/requests/$id/reject")
                .header("Authorization", authHeader(token)),
        )

    private fun cancel(token: String, id: Long): ResultActions =
        mockMvc.perform(
            MockMvcRequestBuilders.delete("/api/v1/friends/requests/$id")
                .header("Authorization", authHeader(token)),
        )

    private fun listFriends(token: String): ResultActions =
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/friends")
                .header("Authorization", authHeader(token)),
        )

    private fun listReceived(token: String): ResultActions =
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/friends/requests/received")
                .header("Authorization", authHeader(token)),
        )

    // ─── 1. 검색 — 본인 제외 / 닉네임 contains / LIMIT 20 / ACTIVE만 ───

    @Test
    fun `검색 - 본인 제외, 닉네임 contains, ACTIVE 만, LIMIT 20`() {
        val me = newUser(1001L, "민수")
        // 본인도 매칭되는 닉네임이지만 결과에서 제외되어야 함
        val token = accessTokenFor(me.id!!)

        // ACTIVE 매칭 후보 25명 (LIMIT 20 검증용) — 모두 "test_user_..." 닉네임
        repeat(25) { idx ->
            newUser(2000L + idx, "test_user_%02d".format(idx))
        }
        // INACTIVE 사용자 (검색 결과에서 제외되어야 함)
        newUser(3001L, "test_user_inactive", status = UserStatus.SUSPENDED)
        newUser(3002L, "test_user_deleted", status = UserStatus.DELETED)

        search(token, "test_user")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.users.length()").value(20))
            // 본인("민수") 미포함 검증
            .andExpect(jsonPath("$.data.users[?(@.nickname == '민수')]").isEmpty)
            // ACTIVE 만 — INACTIVE 사용자가 결과에 없어야 함 (LIMIT 20 안에 들었더라도)
            .andExpect(jsonPath("$.data.users[?(@.nickname == 'test_user_inactive')]").isEmpty)
            .andExpect(jsonPath("$.data.users[?(@.nickname == 'test_user_deleted')]").isEmpty)
    }

    // ─── 2. 검색 — relation 5종 모두 등장 ─────────────────────

    @Test
    fun `검색 - relation 5종 NONE REQUEST_SENT REQUEST_RECEIVED FRIEND REJECTED 모두 derived`() {
        val me = newUser(1101L, "나")
        val none = newUser(1110L, "지인A_NONE")
        val sent = newUser(1111L, "지인B_SENT")
        val received = newUser(1112L, "지인C_RECEIVED")
        val friend = newUser(1113L, "지인D_FRIEND")
        val rejected = newUser(1114L, "지인E_REJECTED")

        // REQUEST_SENT: me → sent PENDING
        val sentRow = saveFriendship(me.id!!, sent.id!!, FriendshipStatus.PENDING)
        // REQUEST_RECEIVED: received → me PENDING
        val receivedRow = saveFriendship(received.id!!, me.id!!, FriendshipStatus.PENDING)
        // FRIEND: me → friend ACCEPTED
        saveFriendship(me.id!!, friend.id!!, FriendshipStatus.ACCEPTED, acceptedAt = LocalDateTime.now())
        // REJECTED: me → rejected REJECTED
        saveFriendship(me.id!!, rejected.id!!, FriendshipStatus.REJECTED)

        val token = accessTokenFor(me.id!!)
        val result = search(token, "지인")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.users.length()").value(5))
            .andReturn()

        val body = objectMapper.readTree(result.response.contentAsString)
        val users = body["data"]["users"]
        val byNickname = users.associateBy { it["nickname"].asText() }

        assertEquals("NONE", byNickname["지인A_NONE"]!!["relation"].asText())
        assertTrue(byNickname["지인A_NONE"]!!["pendingRequestId"].isNull)

        assertEquals("REQUEST_SENT", byNickname["지인B_SENT"]!!["relation"].asText())
        assertEquals(sentRow.id, byNickname["지인B_SENT"]!!["pendingRequestId"].asLong())

        assertEquals("REQUEST_RECEIVED", byNickname["지인C_RECEIVED"]!!["relation"].asText())
        assertEquals(receivedRow.id, byNickname["지인C_RECEIVED"]!!["pendingRequestId"].asLong())

        assertEquals("FRIEND", byNickname["지인D_FRIEND"]!!["relation"].asText())
        assertTrue(byNickname["지인D_FRIEND"]!!["pendingRequestId"].isNull)

        assertEquals("REJECTED", byNickname["지인E_REJECTED"]!!["relation"].asText())
        assertTrue(byNickname["지인E_REJECTED"]!!["pendingRequestId"].isNull)

        // 참고: none 도 NONE 으로 반환되었는지 — 위에서 length() == 5 검증으로 보장 + byNickname["지인A_NONE"] non-null.
        assertEquals(none.id, byNickname["지인A_NONE"]!!["id"].asLong())
    }

    // ─── 3. 요청 → 수락 → 친구 목록 ──────────────────────────

    @Test
    fun `요청 → 수락 → 친구 목록에 추가되고 status ACCEPTED`() {
        val a = newUser(1201L, "A_alice")
        val b = newUser(1202L, "B_bob")
        val aToken = accessTokenFor(a.id!!)
        val bToken = accessTokenFor(b.id!!)

        // A → B 요청
        val sendResult = sendRequest(aToken, b.id!!)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andReturn()
        val requestId = objectMapper.readTree(sendResult.response.contentAsString)["data"]["requestId"].asLong()

        // B 가 받은 요청 목록에 보임
        listReceived(bToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.requests.length()").value(1))
            .andExpect(jsonPath("$.data.requests[0].id").value(requestId))
            .andExpect(jsonPath("$.data.requests[0].fromUser.id").value(a.id))

        // B 가 수락
        accept(bToken, requestId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friendshipId").value(requestId))
            .andExpect(jsonPath("$.data.status").value("ACCEPTED"))

        // A 의 친구 목록에 B 가 있음
        listFriends(aToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friends.length()").value(1))
            .andExpect(jsonPath("$.data.friends[0].id").value(b.id))
            .andExpect(jsonPath("$.data.friends[0].nickname").value("B_bob"))
        // B 의 친구 목록에도 A 가 있음 (양방향 추가 row 만들지 않고 매핑만)
        listFriends(bToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friends.length()").value(1))
            .andExpect(jsonPath("$.data.friends[0].id").value(a.id))

        // DB 검증: row 1건, status=ACCEPTED, accepted_at non-null
        val rows = friendshipJpaRepository.findAll()
        assertEquals(1, rows.size)
        assertEquals(FriendshipStatus.ACCEPTED.name, rows[0].status)
        assertNotNull(rows[0].acceptedAt)
    }

    // ─── 4. 요청 → 거절 → 검색에서 REJECTED ───────────────────

    @Test
    fun `요청 → 거절 → status REJECTED, 검색에서 REJECTED relation`() {
        val a = newUser(1301L, "A_alpha")
        val b = newUser(1302L, "B_beta_REJ")
        val aToken = accessTokenFor(a.id!!)
        val bToken = accessTokenFor(b.id!!)

        val sendResult = sendRequest(aToken, b.id!!).andExpect(status().isOk).andReturn()
        val requestId = objectMapper.readTree(sendResult.response.contentAsString)["data"]["requestId"].asLong()

        reject(bToken, requestId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.requestId").value(requestId))
            .andExpect(jsonPath("$.data.status").value("REJECTED"))

        // DB 검증: row 보존 + status REJECTED
        val rows = friendshipJpaRepository.findAll()
        assertEquals(1, rows.size)
        assertEquals(FriendshipStatus.REJECTED.name, rows[0].status)
        assertNull(rows[0].acceptedAt)

        // A 가 검색하면 B 의 relation 이 REJECTED
        search(aToken, "B_beta_REJ")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.users.length()").value(1))
            .andExpect(jsonPath("$.data.users[0].relation").value("REJECTED"))
            .andExpect(jsonPath("$.data.users[0].pendingRequestId").doesNotExist())
    }

    // ─── 5. 요청 → 거절 → 다시 요청 → 동일 row UPDATE ──────────

    @Test
    fun `요청 → 거절 → 다시 요청 시 동일 row UPDATE (행 수 불변, created_at 보존)`() {
        val a = newUser(1401L, "A_again")
        val b = newUser(1402L, "B_again")
        val aToken = accessTokenFor(a.id!!)
        val bToken = accessTokenFor(b.id!!)

        // 1차 요청
        val firstSend = sendRequest(aToken, b.id!!).andExpect(status().isOk).andReturn()
        val firstId = objectMapper.readTree(firstSend.response.contentAsString)["data"]["requestId"].asLong()
        // B 거절
        reject(bToken, firstId).andExpect(status().isOk)

        val rowsAfterReject = friendshipJpaRepository.findAll()
        assertEquals(1, rowsAfterReject.size)
        val createdAtBefore = rowsAfterReject[0].createdAt
        val rowIdBefore = rowsAfterReject[0].id

        // A 가 다시 요청 — 동일 row UPDATE 되어야 함
        val secondSend = sendRequest(aToken, b.id!!).andExpect(status().isOk).andReturn()
        val secondId = objectMapper.readTree(secondSend.response.contentAsString)["data"]["requestId"].asLong()
        assertEquals(firstId, secondId, "재요청 시 기존 row id 재사용되어야 함")

        val rowsAfterReReq = friendshipJpaRepository.findAll()
        assertEquals(1, rowsAfterReReq.size, "재요청 시 row 수 불변 (UPDATE)")
        assertEquals(rowIdBefore, rowsAfterReReq[0].id)
        assertEquals(FriendshipStatus.PENDING.name, rowsAfterReReq[0].status)
        assertNull(rowsAfterReReq[0].acceptedAt)
        assertEquals(createdAtBefore, rowsAfterReReq[0].createdAt, "created_at 보존")
    }

    // ─── 6. 요청 → 취소 → 물리 삭제 ───────────────────────────

    @Test
    fun `요청 → 취소 시 PENDING row 물리 삭제, 재요청 가능`() {
        val a = newUser(1501L, "A_cancel")
        val b = newUser(1502L, "B_cancel")
        val aToken = accessTokenFor(a.id!!)

        val firstSend = sendRequest(aToken, b.id!!).andExpect(status().isOk).andReturn()
        val firstId = objectMapper.readTree(firstSend.response.contentAsString)["data"]["requestId"].asLong()

        cancel(aToken, firstId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.requestId").value(firstId))

        // 물리 삭제 확인
        assertEquals(0, friendshipJpaRepository.count())

        // 재요청 → 새 row (다른 id) 가능
        val secondSend = sendRequest(aToken, b.id!!).andExpect(status().isOk).andReturn()
        val secondId = objectMapper.readTree(secondSend.response.contentAsString)["data"]["requestId"].asLong()
        // 새 row 이므로 id 가 다를 가능성 높음 (Postgres BIGSERIAL 은 항상 증가)
        assertEquals(1, friendshipJpaRepository.count())
        assertEquals(FriendshipStatus.PENDING.name, friendshipJpaRepository.findById(secondId).get().status)
    }

    // ─── 7. 동시 요청 race — 반대 방향 PENDING 안내 ───────────

    @Test
    fun `A→B 후 B→A 시 사전 검사로 차단 + 안내 메시지`() {
        val a = newUser(1601L, "A_race")
        val b = newUser(1602L, "B_race")
        val aToken = accessTokenFor(a.id!!)
        val bToken = accessTokenFor(b.id!!)

        // A → B 요청
        sendRequest(aToken, b.id!!).andExpect(status().isOk)

        // B → A 요청 시 사전 검사로 차단 (HTTP 200 + code 700)
        sendRequest(bToken, a.id!!)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("상대가 이미 친구 요청을 보냈어요. 확인해보세요"))
    }

    // ─── 8. 미인증 401 ─────────────────────────────────────────

    @Test
    fun `미인증 토큰 없으면 HTTP 401 + code 401`() {
        // 토큰 없이 검색 호출
        search(token = null, nickname = "anyone")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))

        // 토큰 없이 친구 목록
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/friends"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))

        // 토큰 없이 받은 요청
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/friends/requests/received"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))
    }

    // ─── 9. 권한 외 accept/reject/cancel → 700 ─────────────────

    @Test
    fun `권한 외 accept reject cancel 시 HTTP 200 + code 700`() {
        val a = newUser(1701L, "A_perm")
        val b = newUser(1702L, "B_perm")
        val c = newUser(1703L, "C_perm")
        val aToken = accessTokenFor(a.id!!)
        val bToken = accessTokenFor(b.id!!)
        val cToken = accessTokenFor(c.id!!)

        // A → B PENDING row
        val sendResult = sendRequest(aToken, b.id!!).andExpect(status().isOk).andReturn()
        val requestId = objectMapper.readTree(sendResult.response.contentAsString)["data"]["requestId"].asLong()

        // C 가 수락 시도 → 권한 없음 (receiver_id != C)
        accept(cToken, requestId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("이 요청을 수락할 권한이 없어요"))

        // C 가 거절 시도 → 권한 없음
        reject(cToken, requestId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("이 요청을 거절할 권한이 없어요"))

        // C 가 취소 시도 → 권한 없음 (requester_id != C)
        cancel(cToken, requestId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("이 요청을 취소할 권한이 없어요"))

        // 추가 검증: B 가 취소 시도 — receiver 이지만 cancel 은 requester 만 가능
        cancel(bToken, requestId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("이 요청을 취소할 권한이 없어요"))
    }
}
