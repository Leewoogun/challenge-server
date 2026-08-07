package com.lwg.challenge.service.user

import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.user.UserStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

/**
 * `UserService.updateFcmToken` 단위 테스트 (push-fcm T-B1).
 *
 * Mockito 대신 **fcm_token 컬럼의 의미를 실제로 흉내내는 fake** 를 쓴다 (레포 선례:
 * `ChallengeCommandServiceTest`). 호출 여부(verify)만 보면 "clearFcmTokenOwnedByOthers 를 불렀다"
 * 까지만 알 수 있는데, 우리가 진짜 막으려는 것은 **끝난 뒤 두 계정이 같은 토큰을 갖고 있는 상태**다.
 * 그래서 저장 결과를 그대로 읽어 확인한다.
 */
class UserServiceFcmTokenTest {

    private val repository = FakeUserRepository()
    private val service = UserService(repository)

    private companion object {
        const val DEVICE_TOKEN = "fcm-token-of-one-physical-device"
        const val ALICE = 1L
        const val BOB = 2L
        const val CAROL = 3L
    }

    private fun givenUsers() {
        repository.put(user(ALICE))
        repository.put(user(BOB))
        repository.put(user(CAROL))
    }

    /**
     * 🔴 이 feature 의 핵심 회귀 방지 — 시나리오 6 (한 기기에서 계정 전환).
     *
     * 이게 깨지면 **한 기기가 두 계정의 알림을 받는다.**
     */
    @Test
    fun `다른 계정이 같은 토큰을 등록하면 이전 계정의 토큰이 NULL 로 밀린다`() {
        givenUsers()
        service.updateFcmToken(ALICE, DEVICE_TOKEN)

        service.updateFcmToken(BOB, DEVICE_TOKEN)

        assertNull(repository.fcmTokenOf(ALICE), "이전 소유자 ALICE 의 토큰이 남아 있으면 알림이 이중 발송된다")
        assertEquals(DEVICE_TOKEN, repository.fcmTokenOf(BOB))
        assertEquals(
            1,
            repository.all().count { it.fcmToken == DEVICE_TOKEN },
            "한 토큰을 가진 사용자는 언제나 정확히 1명이어야 한다",
        )
    }

    @Test
    fun `무관한 계정의 다른 토큰은 건드리지 않는다`() {
        givenUsers()
        service.updateFcmToken(CAROL, "another-device-token")

        service.updateFcmToken(ALICE, DEVICE_TOKEN)

        assertEquals("another-device-token", repository.fcmTokenOf(CAROL))
    }

    /**
     * 모바일이 로그인 직후와 `onNewToken` 양쪽에서 부르므로 중복 호출이 **정상 경로**다.
     * `id <> :me` 제외 조건이 빠지면 자기 토큰까지 지워져 여기서 null 이 나온다.
     */
    @Test
    fun `같은 사용자가 같은 토큰을 두 번 보내도 결과가 같다 - 멱등`() {
        givenUsers()

        service.updateFcmToken(ALICE, DEVICE_TOKEN)
        service.updateFcmToken(ALICE, DEVICE_TOKEN)
        service.updateFcmToken(ALICE, DEVICE_TOKEN)

        assertEquals(DEVICE_TOKEN, repository.fcmTokenOf(ALICE))
    }

    @Test
    fun `기기의 토큰이 갱신되면 이전 토큰을 대체한다`() {
        givenUsers()
        service.updateFcmToken(ALICE, DEVICE_TOKEN)

        service.updateFcmToken(ALICE, "rotated-token")

        assertEquals("rotated-token", repository.fcmTokenOf(ALICE))
    }

    /**
     * 소유권 이전 단계가 통째로 삭제되는 회귀를 잡는다.
     *
     * 위 결과 검증만으로도 대부분 잡히지만, 이 호출 자체가 **DB 에 UNIQUE 제약이 없어서**
     * 필요한 것이라는 사실을 코드에 고정해 둔다.
     */
    @Test
    fun `등록 시 소유권 이전 질의가 반드시 선행된다`() {
        givenUsers()

        service.updateFcmToken(ALICE, DEVICE_TOKEN)

        assertTrue(
            repository.calls.contains("clearFcmTokenOwnedByOthers($DEVICE_TOKEN, except=$ALICE)"),
            "소유권 이전 질의가 호출되지 않았다 — 실제 호출 로그=${repository.calls}",
        )
    }

    /**
     * 탈퇴·삭제된 계정의 access token 이 아직 만료되지 않은 경우.
     * `getMe` 와 같은 처리(401)로 맞춘다 — 조용히 200 을 주면 모바일은 등록됐다고 믿는다.
     */
    @Test
    fun `토큰의 user 가 DB 에 없으면 UnauthorizedException`() {
        assertThrows<UnauthorizedException> {
            service.updateFcmToken(9999L, DEVICE_TOKEN)
        }
    }

    private fun user(id: Long) = User(
        id = id,
        kakaoId = 1000L + id,
        nickname = "user$id",
        profileImageUrl = null,
        phoneNumber = null,
        phoneVerified = false,
        fcmToken = null,
        status = UserStatus.ACTIVE,
        createdAt = LocalDateTime.of(2026, 8, 6, 0, 0),
        updatedAt = LocalDateTime.of(2026, 8, 6, 0, 0),
    )
}

/**
 * `users` 테이블의 fcm_token / refresh_token_hash 갱신 의미를 그대로 흉내내는 fake.
 *
 * `clearFcmTokenOwnedByOthers` 의 `AND id <> :me` 를 **여기서도 똑같이 재현**하는 것이 중요하다.
 * fake 가 조건을 빠뜨리면 프로덕션 쿼리가 틀려도 테스트가 통과한다.
 */
internal class FakeUserRepository : UserRepository {

    private val store = LinkedHashMap<Long, User>()

    /** 호출 순서 기록 — 소유권 이전 단계 삭제를 잡기 위함. */
    val calls = mutableListOf<String>()

    fun put(u: User) {
        store[u.id!!] = u
    }

    fun all(): List<User> = store.values.toList()

    fun fcmTokenOf(id: Long): String? = store[id]?.fcmToken

    fun refreshHashOf(id: Long): String? = store[id]?.refreshTokenHash

    override fun findById(id: Long): User? = store[id]

    override fun findByKakaoId(kakaoId: Long): User? = store.values.firstOrNull { it.kakaoId == kakaoId }

    override fun findAllByIds(ids: Collection<Long>): Map<Long, User> =
        ids.mapNotNull { store[it] }.associateBy { it.id!! }

    override fun save(user: User): User = user.also { put(it) }

    override fun updateRefreshTokenHash(userId: Long, hash: String?, issuedAt: LocalDateTime?) {
        calls += "updateRefreshTokenHash($userId, hash=$hash)"
        val current = store[userId] ?: return
        store[userId] = current.copy(refreshTokenHash = hash, refreshTokenIssuedAt = issuedAt)
    }

    override fun clearFcmTokenOwnedByOthers(fcmToken: String, exceptUserId: Long): Int {
        calls += "clearFcmTokenOwnedByOthers($fcmToken, except=$exceptUserId)"
        val targets = store.values.filter { it.fcmToken == fcmToken && it.id != exceptUserId }
        targets.forEach { store[it.id!!] = it.copy(fcmToken = null) }
        return targets.size
    }

    override fun updateFcmToken(userId: Long, fcmToken: String?): Int {
        calls += "updateFcmToken($userId, token=$fcmToken)"
        val current = store[userId] ?: return 0
        store[userId] = current.copy(fcmToken = fcmToken)
        return 1
    }
}
