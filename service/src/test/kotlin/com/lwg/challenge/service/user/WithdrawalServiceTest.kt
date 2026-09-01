package com.lwg.challenge.service.user

import com.lwg.challenge.domain.auth.KakaoServerException
import com.lwg.challenge.domain.auth.KakaoUnlinkPort
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.ReceivedChallenge
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.DialogException
import com.lwg.challenge.domain.friend.Friend
import com.lwg.challenge.domain.friend.FriendRequest
import com.lwg.challenge.domain.friend.Friendship
import com.lwg.challenge.domain.friend.FriendshipRepository
import com.lwg.challenge.domain.friend.FriendshipStatus
import com.lwg.challenge.domain.friend.UserSearchResult
import com.lwg.challenge.domain.notification.Notification
import com.lwg.challenge.domain.notification.NotificationRepository
import com.lwg.challenge.domain.notification.NotificationType
import com.lwg.challenge.domain.photo.PhotoStorage
import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationRepository
import com.lwg.challenge.domain.verification.VerificationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * `WithdrawalService` 단위 테스트 (mypage T-B2).
 *
 * ## 이 테스트가 지키는 것은 **순서**다
 *
 * 익명화 자체는 한 줄짜리 벌크 UPDATE 라 틀릴 여지가 적다. 틀리기 쉬운 것은 *"무엇을 먼저
 * 하는가"* 이고, 그게 틀렸을 때의 피해는 **복구 불가**다 (`WithdrawalService` KDoc):
 *
 * - unlink 보다 DB 를 먼저 하면 → 익명화는 끝났는데 카카오 연결이 남고 `kakao_id` 가 없어
 *   **재시도할 대상조차 잃는다.**
 * - 사진을 커밋 전에 지우면 → 롤백돼도 파일은 돌아오지 않아 **row 는 살아 있는데 사진만 없다.**
 *
 * 그래서 단언의 절반이 *"이건 호출되지 **않았다**"* 이다.
 *
 * ⚠️ **트랜잭션 경계는 여기서 검증하지 않는다.** 단위 테스트는 [WithdrawalTransactionalWorker]
 * 를 실물로 직접 주입하므로 Spring 프록시가 없고 `@Transactional` 이 돌지 않는다. 이 테스트가
 * 보는 것은 호출 순서와 인자이며, 프록시가 필요한 이유는 그 클래스 KDoc 이 설명한다.
 *
 * fake 스타일은 `AuthServiceLogoutTest` 와 같다 — `FakeUserRepository` 를 그대로 재사용한다.
 */
class WithdrawalServiceTest {

    private val users = FakeUserRepository()
    private val friendships = RecordingFriendshipRepository()
    private val verifications = RecordingVerificationRepository()
    private val challenges = RecordingChallengeRepository()
    private val notifications = RecordingNotificationRepository()
    private val photoStorage = RecordingPhotoStorage()
    private val kakao = RecordingKakaoUnlinkPort()

    private val service = WithdrawalService(
        userRepository = users,
        kakaoUnlinkPort = kakao,
        worker = WithdrawalTransactionalWorker(users, friendships, verifications, challenges, notifications),
        photoStorage = photoStorage,
        clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC),
    )

    private companion object {
        const val ME = 1L
        const val FRIEND = 2L
        const val MY_KAKAO_ID = 4883170475L

        val NOW: LocalDateTime = LocalDateTime.of(2026, 8, 26, 0, 0)
        val CREATED_AT: LocalDateTime = LocalDateTime.of(2026, 5, 7, 10, 10, 42)

        val CHALLENGE_DATE: LocalDate = LocalDate.of(2026, 8, 25)
        val DEADLINE: LocalDateTime = LocalDateTime.of(2026, 8, 25, 23, 59, 59)

        const val KEY_A = "7/1/9f3c8a1b2e4d5f60a1b2c3d4e5f60718.jpg"
        const val KEY_B = "8/1/1a2b3c4d5e6f708192a3b4c5d6e7f809.jpg"
    }

    private fun givenActiveUser(kakaoId: Long? = MY_KAKAO_ID, status: UserStatus = UserStatus.ACTIVE) {
        users.put(
            User(
                id = ME,
                kakaoId = kakaoId,
                nickname = "이우건",
                profileImageUrl = "http://img1.kakaocdn.net/u/42.jpg",
                phoneNumber = "a".repeat(64),
                phoneVerified = true,
                fcmToken = "fcm-token-of-this-device",
                status = status,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
                refreshTokenHash = "b".repeat(64),
                refreshTokenIssuedAt = CREATED_AT,
            ),
        )
    }

    private fun givenFriendship() {
        friendships.put(
            Friendship(
                id = 10L,
                requesterId = ME,
                receiverId = FRIEND,
                status = FriendshipStatus.ACCEPTED,
                createdAt = CREATED_AT,
                acceptedAt = CREATED_AT,
            ),
        )
    }

    private fun givenVerification(id: Long, challengeId: Long, photoKey: String?, userId: Long = ME) {
        verifications.put(
            Verification(
                id = id,
                challengeId = challengeId,
                userId = userId,
                status = if (photoKey == null) VerificationStatus.PENDING else VerificationStatus.VERIFIED,
                photoUrl = photoKey,
                verifiedAt = if (photoKey == null) null else CREATED_AT,
                createdAt = CREATED_AT,
            ),
        )
    }

    /**
     * `opponentMission` 은 `PENDING` 이면 항상 null 이다 (`Challenge` KDoc) — 그 규약을 지켜
     * 만든다. 상태별로 딴 값을 쓰면 fake 가 실제와 다른 세계를 만든다.
     */
    private fun givenChallenge(
        id: Long,
        challengerId: Long,
        opponentId: Long,
        status: ChallengeStatus,
    ) {
        challenges.put(
            Challenge(
                id = id,
                challengerId = challengerId,
                opponentId = opponentId,
                challengerMission = "물 2L 마시기",
                opponentMission = if (status == ChallengeStatus.PENDING) null else "계단 20층 오르기",
                betContent = "커피 사기",
                challengeDate = CHALLENGE_DATE,
                deadline = DEADLINE,
                status = status,
                result = null,
                createdAt = CREATED_AT,
                completedAt = if (status == ChallengeStatus.COMPLETED) NOW else null,
            ),
        )
    }

    private fun givenNotification(id: Long, userId: Long = ME) {
        notifications.put(
            Notification(
                id = id,
                userId = userId,
                type = NotificationType.CHALLENGE_REQUEST,
                title = "새로운 도전장이 도착했어요",
                body = "이우건님이 도전장을 보냈어요",
                referenceId = 7L,
                isRead = false,
                createdAt = CREATED_AT,
            ),
        )
    }

    // ─── 1. 정상 경로 ─────────────────────────────────────────────

    @Test
    fun `정상 탈퇴 - 카카오 연결 해제 후 익명화하고 사진을 지운다`() {
        givenActiveUser()
        givenFriendship()
        givenVerification(id = 100L, challengeId = 7L, photoKey = KEY_A)
        givenVerification(id = 101L, challengeId = 8L, photoKey = KEY_B)

        service.withdraw(ME)

        assertEquals(listOf(MY_KAKAO_ID), kakao.unlinked, "카카오 연결 해제가 호출돼야 한다")

        val after = users.findById(ME)!!
        assertNull(after.kakaoId, "kakao_id 가 남으면 재로그인이 탈퇴 계정을 부활시킨다")
        assertEquals(WithdrawalService.ANONYMIZED_NICKNAME, after.nickname)
        assertNull(after.profileImageUrl)
        assertNull(after.phoneNumber)
        assertFalse(after.phoneVerified)
        assertNull(after.fcmToken, "fcm_token 이 남으면 탈퇴자 기기로 알림이 계속 간다")
        assertNull(after.refreshTokenHash, "refresh hash 가 남으면 세션이 살아 있다")
        assertNull(after.refreshTokenIssuedAt)
        assertEquals(UserStatus.DELETED, after.status)
        assertEquals(NOW, after.updatedAt, "벌크 UPDATE 라 @PreUpdate 가 안 돈다 — 직접 써야 한다")

        // 🔴 id / created_at 은 건드리지 않는다 — 상대의 챌린지가 FK 로 이 row 를 가리킨다.
        assertEquals(ME, after.id)
        assertEquals(CREATED_AT, after.createdAt)

        assertEquals(1, friendships.deletedForUsers.size, "friendships 는 물리 삭제 대상이다")
        assertTrue(friendships.all().isEmpty())

        assertEquals(listOf(KEY_A, KEY_B), photoStorage.deleted, "수집된 key 마다 파일을 지워야 한다")
    }

    /**
     * 🔴 **row 와 판정 근거는 남고 사진만 사라진다.**
     *
     * `status`/`verified_at` 까지 지우면 *"그날 인증했다"* 는 사실이 사라져 **상대의 챌린지 결과가
     * 근거를 잃는다.** 탈퇴자가 아닌 사람의 기록이 무너지는 것이라 정책상 금지다.
     */
    @Test
    fun `탈퇴해도 verification row 와 status 는 보존되고 photo key 만 NULL 이 된다`() {
        givenActiveUser()
        givenVerification(id = 100L, challengeId = 7L, photoKey = KEY_A)

        service.withdraw(ME)

        val mine = verifications.all().single { it.userId == ME }
        assertEquals(VerificationStatus.VERIFIED, mine.status, "인증했다는 사실은 보존한다")
        assertEquals(CREATED_AT, mine.verifiedAt, "verified_at 도 보존한다")
        assertNull(mine.photoUrl, "사진 key 는 지운다 (개인정보)")
    }

    @Test
    fun `남의 verification 은 건드리지 않는다`() {
        givenActiveUser()
        givenVerification(id = 100L, challengeId = 7L, photoKey = KEY_A)
        givenVerification(id = 200L, challengeId = 7L, photoKey = KEY_B, userId = FRIEND)

        service.withdraw(ME)

        val others = verifications.all().single { it.userId == FRIEND }
        assertEquals(KEY_B, others.photoUrl, "상대의 사진 key 는 그대로여야 한다")
        assertEquals(listOf(KEY_A), photoStorage.deleted, "상대의 사진 파일을 지우면 안 된다")
    }

    // ─── 1-b. PENDING 챌린지 / 수신함 정리 (T-B4) ─────────────────
    //
    // 🔴 이 정책은 **절반이 반대 방향**이다 — 보낸 PENDING 은 지우고 받은 PENDING 은 둔다.
    // 방향을 헷갈리면 컴파일도 되고 "삭제 테스트" 도 통과한다. 그래서 양쪽을 다 고정한다.

    /**
     * 남겨 두면 상대가 수락해 **결과가 이미 정해진 챌린지**가 시작된다 — 탈퇴자는 로그인이
     * 불가해 인증을 올릴 수 없다. `PENDING` 에는 보존할 증거가 0 이라 지우는 것이 안전하다.
     */
    @Test
    fun `탈퇴하면 내가 보낸 PENDING 챌린지가 삭제된다`() {
        givenActiveUser()
        givenChallenge(id = 300L, challengerId = ME, opponentId = FRIEND, status = ChallengeStatus.PENDING)

        service.withdraw(ME)

        assertEquals(listOf(ME), challenges.deletedPendingForUsers, "보낸 PENDING 삭제가 호출돼야 한다")
        assertTrue(challenges.all().isEmpty(), "내가 보낸 PENDING 은 남아 있으면 안 된다")
    }

    /**
     * 🔴 **정책의 반대쪽 절반이다.**
     *
     * 받은 도전장은 수락 권한이 탈퇴자에게 있는데 그가 없으므로 **아무도 수락할 수 없다** —
     * 마감이 지나면 자연 만료된다. 여기서 같이 지우면 **살아 있는 상대가 자기가 보낸 도전장을
     * 잃는다.**
     */
    @Test
    fun `탈퇴해도 내가 받은 PENDING 챌린지는 삭제되지 않는다`() {
        givenActiveUser()
        givenChallenge(id = 301L, challengerId = FRIEND, opponentId = ME, status = ChallengeStatus.PENDING)

        service.withdraw(ME)

        val remaining = challenges.all().single()
        assertEquals(301L, remaining.id, "받은 PENDING 은 그대로 남아 자연 만료돼야 한다")
        assertEquals(FRIEND, remaining.challengerId)
    }

    /**
     * 🔴 **이 테스트가 T-B4 에서 가장 위험한 회귀를 막는다.**
     *
     * `IN_PROGRESS`/`COMPLETED` 에는 계약서·서명·사진·결과가 딸려 있고 그것은 **상대의
     * 기록이기도 하다.** 삭제 조건이 조금이라도 넓어지면(예: `status` 를 파라미터로 여는 순간)
     * 탈퇴 한 번이 탈퇴하지 않은 사람의 과거까지 지운다.
     *
     * 그래서 *"남아 있다"* 만 보지 않고 **호출 자체**를 고정한다 — 포트에서 삭제 계열로 나가는
     * 것은 `deletePendingSentBy` 단 한 번뿐이어야 한다.
     */
    @Test
    fun `탈퇴해도 IN_PROGRESS 와 COMPLETED 챌린지는 삭제되지 않는다`() {
        givenActiveUser()
        givenChallenge(id = 310L, challengerId = ME, opponentId = FRIEND, status = ChallengeStatus.IN_PROGRESS)
        givenChallenge(id = 311L, challengerId = ME, opponentId = FRIEND, status = ChallengeStatus.COMPLETED)
        givenChallenge(id = 312L, challengerId = ME, opponentId = FRIEND, status = ChallengeStatus.PENDING)

        service.withdraw(ME)

        assertEquals(
            listOf(310L, 311L),
            challenges.all().map { it.id },
            "PENDING 만 사라지고 나머지는 그대로여야 한다",
        )
        assertEquals(1, challenges.deletedPendingForUsers.size, "삭제 쿼리는 한 번만 나가야 한다")
        assertEquals(
            emptyList<Long>(),
            challenges.deletedIds,
            "단건 삭제(deleteById)로 보존 대상을 지우는 경로가 있으면 안 된다",
        )
    }

    /** 수신함은 본인만 읽는 개인 데이터라 "기록 보존" 이 아니라 개인정보 삭제 축이다. */
    @Test
    fun `탈퇴하면 내 notifications 가 삭제된다`() {
        givenActiveUser()
        givenNotification(id = 400L)
        givenNotification(id = 401L)

        service.withdraw(ME)

        assertEquals(listOf(ME), notifications.deletedForUsers, "수신함 삭제가 호출돼야 한다")
        assertTrue(notifications.all().isEmpty())
    }

    /** 🔴 개인 데이터라는 근거가 성립하려면 **내 것만** 지워야 한다. */
    @Test
    fun `탈퇴해도 상대의 notifications 는 삭제되지 않는다`() {
        givenActiveUser()
        givenNotification(id = 400L)
        givenNotification(id = 500L, userId = FRIEND)

        service.withdraw(ME)

        assertEquals(listOf(500L), notifications.all().map { it.id }, "상대의 수신함은 그대로여야 한다")
    }

    // ─── 2. unlink 실패 — 🔴 DB 를 건드리면 안 된다 ────────────────

    /**
     * 🔴 **이 테스트가 이 클래스에서 가장 중요하다.**
     *
     * unlink 가 실패했는데 익명화가 진행되면 계정은 되돌릴 수 없게 익명화됐는데 카카오 연결은
     * 살아 있고, `kakao_id` 를 지워버려 **재시도할 대상조차 잃는다.** 복구 불가 상태다.
     */
    @Test
    fun `unlink 가 실패하면 DB 를 전혀 건드리지 않는다`() {
        givenActiveUser()
        givenFriendship()
        givenVerification(id = 100L, challengeId = 7L, photoKey = KEY_A)
        givenChallenge(id = 300L, challengerId = ME, opponentId = FRIEND, status = ChallengeStatus.PENDING)
        givenNotification(id = 400L)
        kakao.failWith = KakaoServerException("kakao 5xx")

        assertThrows<DialogException> { service.withdraw(ME) }

        assertEquals(emptyList<String>(), users.calls, "익명화가 호출되면 안 된다")
        assertEquals(emptyList<Long>(), friendships.deletedForUsers, "친구 삭제가 호출되면 안 된다")
        assertEquals(emptyList<Long>(), verifications.clearedForUsers, "photo key 제거가 호출되면 안 된다")
        assertEquals(emptyList<Long>(), challenges.deletedPendingForUsers, "PENDING 삭제가 호출되면 안 된다")
        assertEquals(emptyList<Long>(), notifications.deletedForUsers, "수신함 삭제가 호출되면 안 된다")
        assertEquals(emptyList<String>(), photoStorage.deleted, "사진을 지우면 안 된다")

        val after = users.findById(ME)!!
        assertEquals(UserStatus.ACTIVE, after.status, "row 는 ACTIVE 그대로여야 재시도가 가능하다")
        assertEquals(MY_KAKAO_ID, after.kakaoId)
    }

    /** 로그아웃과 달리 탈퇴는 실패를 삼키지 않는다 — 앱이 code 로 성공/실패를 가른다. */
    @Test
    fun `unlink 실패는 code 701 DialogException 이다`() {
        givenActiveUser()
        kakao.failWith = KakaoServerException("kakao 5xx")

        val e = assertThrows<DialogException> { service.withdraw(ME) }

        assertEquals(ResponseCode.DIALOG_ERROR, e.code)
        assertEquals(WithdrawalService.MSG_WITHDRAWAL_FAILED, e.message)
    }

    /**
     * 🔴 카카오 `-101`("연결되지 않은 사용자") 은 **이미 원하는 상태**라 성공이다.
     *
     * ⚠️ HTTP 400 body 를 보고 그렇게 판정하는 곳은 `KakaoOAuthClient.isNotLinked` 이며,
     * [KakaoUnlinkPort] 는 그걸 **정상 반환으로 삼켜** 서비스에 전달한다. 이 테스트가 고정하는
     * 것은 서비스 쪽 계약 — *"포트가 조용히 돌아오면 탈퇴는 그대로 진행된다"* 이다.
     *
     * 이게 깨지면 1차 시도에서 unlink 만 성공하고 DB 커밋이 실패한 사용자가 **영원히 탈퇴하지
     * 못한다.**
     */
    @Test
    fun `unlink 가 이미 연결 없음-101 이면 탈퇴가 정상 진행된다`() {
        givenActiveUser()
        givenVerification(id = 100L, challengeId = 7L, photoKey = KEY_A)
        kakao.alreadyUnlinked = true

        assertDoesNotThrow { service.withdraw(ME) }

        assertEquals(UserStatus.DELETED, users.findById(ME)!!.status)
        assertEquals(listOf(KEY_A), photoStorage.deleted)
    }

    // ─── 3. 멱등성 ────────────────────────────────────────────────

    /**
     * access token 이 최대 1시간 살아 있고 `JwtAuthenticationFilter` 는 status 를 보지 않으므로
     * **재요청이 실제로 가능하다.** 두 번째 호출에서 카카오를 다시 부르면 이미 끊긴 연결에
     * 불필요한 왕복이 생긴다.
     */
    @Test
    fun `이미 탈퇴한 사용자는 아무것도 하지 않고 성공한다`() {
        givenActiveUser(kakaoId = null, status = UserStatus.DELETED)
        givenVerification(id = 100L, challengeId = 7L, photoKey = KEY_A)
        givenChallenge(id = 300L, challengerId = ME, opponentId = FRIEND, status = ChallengeStatus.PENDING)
        givenNotification(id = 400L)

        assertDoesNotThrow { service.withdraw(ME) }

        assertEquals(emptyList<Long>(), kakao.unlinked, "카카오를 다시 부르면 안 된다")
        assertEquals(emptyList<String>(), users.calls)
        assertEquals(emptyList<Long>(), friendships.deletedForUsers)
        assertEquals(emptyList<Long>(), challenges.deletedPendingForUsers, "삭제 쿼리를 다시 보내면 안 된다")
        assertEquals(emptyList<Long>(), notifications.deletedForUsers, "삭제 쿼리를 다시 보내면 안 된다")
        assertEquals(emptyList<String>(), photoStorage.deleted)
    }

    /** 토큰은 유효한데 row 가 없다. 여기서 던지면 앱이 탈퇴 화면에서 빠져나오지 못한다. */
    @Test
    fun `존재하지 않는 사용자를 탈퇴시켜도 예외가 없다`() {
        assertDoesNotThrow { service.withdraw(9999L) }

        assertEquals(emptyList<Long>(), kakao.unlinked)
        assertEquals(emptyList<String>(), users.calls)
    }

    // ─── 4. 사진 삭제의 실패 내성 ─────────────────────────────────

    /**
     * 🔴 여기서 예외가 새면 **이미 커밋된 탈퇴가 실패로 응답된다.** 사용자는 재시도할 텐데
     * 그때는 `status = DELETED` 라 멱등 분기로 빠져 사진은 영영 지워지지 않는다.
     */
    @Test
    fun `사진 파일 삭제가 실패해도 탈퇴는 성공한다`() {
        givenActiveUser()
        givenVerification(id = 100L, challengeId = 7L, photoKey = KEY_A)
        givenVerification(id = 101L, challengeId = 8L, photoKey = KEY_B)
        photoStorage.failOnKey = KEY_A

        assertDoesNotThrow { service.withdraw(ME) }

        assertEquals(UserStatus.DELETED, users.findById(ME)!!.status, "익명화는 이미 커밋됐다")
        assertEquals(
            listOf(KEY_B),
            photoStorage.deleted,
            "한 건이 실패해도 나머지 삭제는 계속돼야 한다",
        )
    }

    @Test
    fun `인증 사진이 없는 사용자는 사진 삭제를 시도하지 않는다`() {
        givenActiveUser()
        givenVerification(id = 100L, challengeId = 7L, photoKey = null) // PENDING

        service.withdraw(ME)

        assertEquals(emptyList<String>(), photoStorage.deleted)
        assertEquals(UserStatus.DELETED, users.findById(ME)!!.status)
    }
}

// ─── fakes ───────────────────────────────────────────────────────

/**
 * [KakaoUnlinkPort] fake.
 *
 * [alreadyUnlinked] 는 카카오 `-101` 을 흉내낸다 — 포트 계약상 **정상 반환**이므로 호출 기록만
 * 남기고 아무것도 던지지 않는다.
 */
private class RecordingKakaoUnlinkPort : KakaoUnlinkPort {
    val unlinked = mutableListOf<Long>()
    var failWith: RuntimeException? = null
    var alreadyUnlinked = false

    override fun unlink(kakaoId: Long) {
        failWith?.let { throw it }
        unlinked += kakaoId
        // alreadyUnlinked 여도 정상 반환한다 — 그게 -101 의 계약이다.
    }
}

private class RecordingFriendshipRepository : FriendshipRepository {
    private val store = mutableListOf<Friendship>()

    /** 🔴 호출 **여부**를 봐야 한다 — unlink 실패 시 `never()` 를 단언하는 축이다. */
    val deletedForUsers = mutableListOf<Long>()

    fun put(f: Friendship) {
        store.add(f)
    }

    fun all(): List<Friendship> = store.toList()

    override fun deleteAllByUserId(userId: Long): Int {
        deletedForUsers += userId
        val before = store.size
        store.removeAll { it.requesterId == userId || it.receiverId == userId }
        return before - store.size
    }

    override fun searchByNickname(me: Long, escapedPattern: String): List<UserSearchResult> = emptyList()
    override fun findById(id: Long): Friendship? = store.firstOrNull { it.id == id }
    override fun findBetween(userA: Long, userB: Long): List<Friendship> = emptyList()
    override fun findFriendsOf(me: Long): List<Friend> = emptyList()
    override fun findReceivedRequestsOf(me: Long): List<FriendRequest> = emptyList()
    override fun save(friendship: Friendship): Friendship = friendship.also { put(it) }
    override fun saveAndFlush(friendship: Friendship): Friendship = save(friendship)
    override fun deleteById(id: Long) {
        store.removeAll { it.id == id }
    }
}

private class RecordingVerificationRepository : VerificationRepository {
    private val store = mutableListOf<Verification>()

    val clearedForUsers = mutableListOf<Long>()

    fun put(v: Verification) {
        store.add(v)
    }

    fun all(): List<Verification> = store.toList()

    override fun findAllByUserId(userId: Long): List<Verification> =
        store.filter { it.userId == userId }

    /** `status`/`verified_at` 을 **그대로 두는 것**까지가 재현 대상이다. */
    override fun clearPhotoKeysByUserId(userId: Long): Int {
        clearedForUsers += userId
        val targets = store.filter { it.userId == userId && it.photoUrl != null }
        targets.forEach { target ->
            store.removeAll { it.id == target.id }
            store.add(target.copy(photoUrl = null))
        }
        return targets.size
    }

    override fun findAllByChallengeIds(challengeIds: Collection<Long>): List<Verification> =
        store.filter { it.challengeId in challengeIds }

    override fun save(verification: Verification): Verification = verification.also { put(it) }
    override fun saveAllAndFlush(verifications: Collection<Verification>): List<Verification> =
        verifications.map { save(it) }
}

/**
 * [ChallengeRepository] fake (mypage T-B4).
 *
 * 🔴 [deletePendingSentBy] 는 **실제 쿼리와 같은 조건**(`challenger = :me AND status = 'PENDING'`)
 * 을 지킨다. 여기서 조건을 느슨하게 쓰면 *"받은 PENDING 은 남는다"* 테스트가 fake 의 관대함
 * 덕에 통과하고, 정작 구현이 틀려도 아무도 모른다.
 *
 * 조회 계열이 전부 빈 목록인 것은 **탈퇴 경로가 챌린지를 읽지 않기** 때문이다 — 읽는 순간
 * 그건 이 클래스가 검증할 흐름이 아니게 된다.
 */
private class RecordingChallengeRepository : ChallengeRepository {
    private val store = LinkedHashMap<Long, Challenge>()

    /** 🔴 호출 **여부와 횟수**를 봐야 한다 — 멱등/unlink 실패 분기의 *"0회"* 를 단언하는 축이다. */
    val deletedPendingForUsers = mutableListOf<Long>()

    /** 🔴 **비어 있어야 한다.** 단건 삭제로 보존 대상을 지우는 경로가 생기면 여기 잡힌다. */
    val deletedIds = mutableListOf<Long>()

    fun put(c: Challenge) {
        store[c.id!!] = c
    }

    fun all(): List<Challenge> = store.values.toList()

    override fun deletePendingSentBy(userId: Long): Int {
        deletedPendingForUsers += userId
        val targets = store.values.filter {
            it.challengerId == userId && it.status == ChallengeStatus.PENDING
        }
        targets.forEach { store.remove(it.id) }
        return targets.size
    }

    override fun deleteById(id: Long) {
        deletedIds += id
        store.remove(id)
    }

    override fun findById(id: Long): Challenge? = store[id]

    override fun findActiveByUser(userId: Long, statuses: Collection<ChallengeStatus>): List<Challenge> = emptyList()
    override fun findRecentlyCompletedByUser(userId: Long, since: LocalDateTime): List<Challenge> = emptyList()
    override fun findCompletedHistoryByUserInMonth(
        userId: Long,
        monthStart: LocalDate,
        nextMonthStart: LocalDate,
    ): List<Challenge> = emptyList()

    override fun findEarliestCompletedChallengeDate(userId: Long): LocalDate? = null
    override fun findReceivedPending(opponentId: Long, now: LocalDateTime): List<ReceivedChallenge> = emptyList()
    override fun findDueByStatus(statuses: Collection<ChallengeStatus>, now: LocalDateTime): List<Challenge> =
        emptyList()

    override fun findConflicting(
        userA: Long,
        userB: Long,
        challengeDate: LocalDate,
        statuses: Collection<ChallengeStatus>,
    ): List<Challenge> = emptyList()

    /** 탈퇴는 챌린지를 **지우기만 한다** — 상태를 바꾸는 순간 그건 다른 정책이다. */
    override fun save(challenge: Challenge): Challenge = error("탈퇴 경로는 챌린지를 저장하지 않는다")
    override fun saveAndFlush(challenge: Challenge): Challenge = error("탈퇴 경로는 챌린지를 저장하지 않는다")
}

/** [NotificationRepository] fake. 탈퇴 경로는 알림을 **지우기만** 하므로 [save] 는 함정이다. */
private class RecordingNotificationRepository : NotificationRepository {
    private val store = mutableListOf<Notification>()

    /** 🔴 호출 **여부**를 봐야 한다 — unlink 실패·멱등 분기의 *"0회"* 를 단언하는 축이다. */
    val deletedForUsers = mutableListOf<Long>()

    fun put(n: Notification) {
        store.add(n)
    }

    fun all(): List<Notification> = store.toList()

    override fun deleteAllByUserId(userId: Long): Int {
        deletedForUsers += userId
        val before = store.size
        store.removeAll { it.userId == userId }
        return before - store.size
    }

    override fun save(notification: Notification): Notification = error("탈퇴 경로는 알림을 저장하지 않는다")

    /** 탈퇴 경로는 **지우기만** 한다 — 목록 조회는 `NotificationQueryServiceTest` 소관이다. */
    override fun findPageByUserId(userId: Long, cursor: Long?, limit: Int): List<Notification> =
        error("탈퇴 경로는 알림을 조회하지 않는다")

    /** 탈퇴 경로는 뱃지를 세지 않는다 — 지워질 수신함의 안 읽은 개수는 의미가 없다. */
    override fun countUnreadByUserId(userId: Long): Long =
        error("탈퇴 경로는 안 읽은 개수를 세지 않는다")

    /** 지울 행을 읽음으로 바꿀 이유가 없다 — 읽음 처리는 `NotificationCommandServiceTest` 소관이다. */
    override fun markAllAsReadByUserId(userId: Long): Int =
        error("탈퇴 경로는 읽음 처리를 하지 않는다")
}

private class RecordingPhotoStorage : PhotoStorage {
    val deleted = mutableListOf<String>()

    /** 이 key 에서만 던진다 — *"한 건 실패가 나머지를 막지 않는다"* 를 보기 위함. */
    var failOnKey: String? = null

    override fun delete(key: String) {
        if (key == failOnKey) throw IllegalStateException("디스크 오류")
        deleted += key
    }

    override fun store(challengeId: Long, userId: Long, bytes: ByteArray): String =
        error("탈퇴 경로는 사진을 저장하지 않는다")

    override fun read(key: String): ByteArray? = error("탈퇴 경로는 사진을 읽지 않는다")
}
