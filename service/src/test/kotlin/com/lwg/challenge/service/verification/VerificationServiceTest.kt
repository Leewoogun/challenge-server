package com.lwg.challenge.service.verification

import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.ReceivedChallenge
import com.lwg.challenge.domain.common.exception.OneButtonDialogException
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.notification.NotificationType
import com.lwg.challenge.domain.photo.PhotoStorage
import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationRepository
import com.lwg.challenge.domain.verification.VerificationStatus
import com.lwg.challenge.service.notification.ChallengeNotificationEvent
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [VerificationService] 단위 테스트 (challenge-verification T-B2·T-B3·T-B4).
 *
 * 커버하는 spec 수용 기준:
 * - 제출 후 `status = VERIFIED`, `photo_url` 에 저장소 key 채워짐, `verified_at` 기록됨
 * - 제출 시 상대에게 `OPPONENT_VERIFIED` 알림이 간다
 * - 🔴 당사자가 아니면 사진을 볼 수 없다 — 제3자 요청은 거부된다
 * - 같은 사용자가 두 번 제출해도 row 가 하나다
 * - `IN_PROGRESS` 가 아닌 챌린지에는 제출할 수 없다
 * - 🔴 재제출은 전면 거부다 (계약 §3) — 그리고 **거부된 제출은 저장소에 아무것도 남기지 않는다**
 * - 🔴 `photoDeleted` 가 **탈퇴로 지워진 사진과 진짜 데이터 이상을 가른다** (mypage T-B1 부록 A)
 *
 * ⚠️ **덮지 못하는 것**: `AFTER_COMMIT` 타이밍. 이 테스트에는 트랜잭션이 없어 알 수 있는 것은
 * *무엇을 발행했는가* 뿐이다 (`NotificationDispatcherTest` 와 같은 한계).
 */
class VerificationServiceTest {

    private val challenges = FakeChallengeRepository()
    private val verifications = FakeVerificationRepository()
    private val users = FakeUserStore()
    private val photos = FakePhotoStorage()
    private val events = RecordingEventPublisher()
    private val clock = Clock.fixed(Instant.parse("2026-08-14T06:32:10Z"), KstTime.ZONE)

    private val service = VerificationService(challenges, verifications, users, photos, clock, events)

    private companion object {
        const val CHALLENGE_ID = 7L
        const val CHALLENGER = 1L
        const val OPPONENT = 2L
        const val STRANGER = 99L

        /** JPEG 매직 넘버(`FF D8 FF`) + 패딩. */
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x11, 0x22)

        /** PNG 시그니처 — JPEG 이 아닌 진짜 이미지 바이트를 흉내낸다. */
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }

    @BeforeEach
    fun setUp() {
        challenges.put(challenge(ChallengeStatus.IN_PROGRESS))
        verifications.save(pending(CHALLENGER))
        verifications.save(pending(OPPONENT))
        // 기본은 양쪽 다 살아 있는 계정이다 — 탈퇴 케이스만 테스트에서 따로 밀어 넣는다.
        users.put(user(CHALLENGER, UserStatus.ACTIVE))
        users.put(user(OPPONENT, UserStatus.ACTIVE))
    }

    private fun user(id: Long, status: UserStatus) = User(
        id = id,
        // 탈퇴는 kakao_id 를 지운다 (V11) — 그 사실까지 흉내내야 fake 가 거짓말을 하지 않는다.
        kakaoId = if (status == UserStatus.DELETED) null else 9000_000L + id,
        nickname = if (status == UserStatus.DELETED) "탈퇴한 사용자" else "사용자$id",
        profileImageUrl = null,
        phoneNumber = null,
        phoneVerified = false,
        fcmToken = null,
        status = status,
        createdAt = LocalDateTime.of(2026, 8, 1, 0, 0),
        updatedAt = LocalDateTime.of(2026, 8, 1, 0, 0),
    )

    private fun challenge(status: ChallengeStatus) = Challenge(
        id = CHALLENGE_ID,
        challengerId = CHALLENGER,
        opponentId = OPPONENT,
        challengerMission = "운동 1시간",
        opponentMission = "책 30쪽",
        betContent = "커피 사기",
        challengeDate = LocalDate.of(2026, 8, 14),
        deadline = LocalDateTime.of(2026, 8, 15, 0, 0),
        status = status,
        result = null,
        createdAt = LocalDateTime.of(2026, 8, 14, 9, 0),
        completedAt = null,
    )

    private fun pending(userId: Long) = Verification(
        id = null,
        challengeId = CHALLENGE_ID,
        userId = userId,
        status = VerificationStatus.PENDING,
        photoUrl = null,
        verifiedAt = null,
        createdAt = LocalDateTime.of(2026, 8, 14, 9, 5),
    )

    // ─── 제출 확정 (T-B2) ────────────────────────────────────────

    /** spec 수용 기준 1. */
    @Test
    fun `제출하면 VERIFIED 로 전이하고 key 와 시각이 기록된다`() {
        val saved = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        assertEquals(VerificationStatus.VERIFIED, saved.status)
        assertEquals(LocalDateTime.now(clock), saved.verifiedAt)
        assertNotNull(saved.photoUrl, "photo_url 에는 URL 이 아니라 저장소 key 가 들어간다 (V9)")
        assertArrayEquals(JPEG, photos.stored[saved.photoUrl!!], "저장소에 제출한 바이트가 그대로 있어야 한다")
    }

    /** 🔴 key 는 내 챌린지·내 자리로 시작한다 — 남의 자리에 쓰지 않는다. */
    @Test
    fun `저장된 key 는 challengeId 와 userId 로 시작한다`() {
        val saved = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        assertTrue(saved.photoUrl!!.startsWith("$CHALLENGE_ID/$CHALLENGER/"), saved.photoUrl)
    }

    /** spec 수용 기준: *"같은 사용자가 두 번 제출해도 row 가 하나다."* */
    @Test
    fun `제출은 row 를 새로 만들지 않고 있는 row 를 채운다`() {
        val before = verifications.all().single { it.userId == CHALLENGER }

        val saved = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        assertEquals(before.id, saved.id)
        assertEquals(2, verifications.all().size, "row 수가 늘면 uq_verifications_challenge_user 가 터진다")
    }

    @Test
    fun `상대의 인증은 건드리지 않는다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val opponent = verifications.all().single { it.userId == OPPONENT }
        assertEquals(VerificationStatus.PENDING, opponent.status)
        assertNull(opponent.photoUrl)
    }

    // ─── 재제출 정책 (계약 §3 — 전면 거부) ───────────────────────

    /**
     * 🔴 multipart 에서는 *"같은 촬영본의 재시도"* 와 *"다른 사진으로 교체"* 를 서버가 구분할 수
     * 없으므로 재제출은 **전면 거부**다. 저장·알림 어느 쪽도 일어나면 안 된다 — 조용히 버려진
     * 사진을 앱이 성공으로 오해하는 경로를 남기지 않는다.
     */
    @Test
    fun `이미 인증했으면 재제출이 거부되고 저장도 알림도 없다`() {
        val first = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        events.clear()

        val e = assertThrows<SnackbarException> {
            service.submit(CHALLENGER, CHALLENGE_ID, JPEG + 0x33.toByte())
        }

        assertEquals(VerificationService.MSG_ALREADY_VERIFIED, e.message)
        assertEquals(setOf(first.photoUrl), photos.stored.keys, "거부된 재제출이 새 파일을 만들면 안 된다")
        assertTrue(events.challengeEvents().isEmpty(), "거부된 재제출이 상대에게 알림을 보내면 안 된다")
        val mine = verifications.all().single { it.userId == CHALLENGER }
        assertEquals(first.photoUrl, mine.photoUrl, "처음 저장된 사진이 그대로여야 한다")
        assertEquals(first.verifiedAt, mine.verifiedAt)
    }

    // ─── 페이로드 거절 ───────────────────────────────────────────

    @Test
    fun `빈 바이트는 거부되고 아무것도 저장되지 않는다`() {
        val e = assertThrows<SnackbarException> { service.submit(CHALLENGER, CHALLENGE_ID, ByteArray(0)) }

        assertEquals(VerificationService.MSG_PHOTO_REQUIRED, e.message)
        assertTrue(photos.stored.isEmpty(), "거부된 제출이 저장소에 파일을 남기면 안 된다")
        assertEquals(VerificationStatus.PENDING, verifications.all().single { it.userId == CHALLENGER }.status)
        assertTrue(events.challengeEvents().isEmpty())
    }

    /** 저장된 바이트는 나중에 `Content-Type: image/jpeg` 로 그대로 서빙된다 — 선언과 실제가 갈리면 안 된다. */
    @Test
    fun `JPEG 매직 넘버가 아니면 거부되고 아무것도 저장되지 않는다`() {
        val e = assertThrows<SnackbarException> { service.submit(CHALLENGER, CHALLENGE_ID, PNG) }

        assertEquals(VerificationService.MSG_NOT_JPEG, e.message)
        assertTrue(photos.stored.isEmpty(), "거부된 제출이 저장소에 파일을 남기면 안 된다")
        assertEquals(VerificationStatus.PENDING, verifications.all().single { it.userId == CHALLENGER }.status)
    }

    // ─── 권한·상태 거절 ──────────────────────────────────────────

    @Test
    fun `없는 챌린지에는 제출할 수 없다`() {
        val e = assertThrows<OneButtonDialogException> { service.submit(CHALLENGER, 12345L, JPEG) }
        assertEquals(VerificationService.MSG_NOT_FOUND, e.message)
    }

    @Test
    fun `제3자는 제출할 수 없고 아무것도 저장되지 않는다`() {
        val e = assertThrows<SnackbarException> { service.submit(STRANGER, CHALLENGE_ID, JPEG) }

        assertEquals(VerificationService.MSG_NOT_MINE, e.message)
        assertTrue(photos.stored.isEmpty(), "거부된 제출이 저장소에 파일을 남기면 안 된다")
    }

    /** spec 수용 기준: *"`IN_PROGRESS` 가 아닌 챌린지에는 제출할 수 없다."* */
    @Test
    fun `IN_PROGRESS 가 아니면 제출할 수 없다`() {
        listOf(ChallengeStatus.PENDING, ChallengeStatus.COMPLETED, ChallengeStatus.REJECTED).forEach { status ->
            challenges.put(challenge(status))
            val e = assertThrows<OneButtonDialogException> { service.submit(CHALLENGER, CHALLENGE_ID, JPEG) }
            assertEquals(VerificationService.MSG_NOT_IN_PROGRESS, e.message, "status=$status")
        }
        assertTrue(photos.stored.isEmpty())
    }

    /**
     * 🔴 권한을 상태보다 먼저 본다. 제3자에게 *"진행 중이 아니에요"* 라고 답하면 남의 챌린지
     * 상태가 새어나간다.
     */
    @Test
    fun `제3자에게는 챌린지 상태를 알려주지 않는다`() {
        challenges.put(challenge(ChallengeStatus.COMPLETED))

        val e = assertThrows<SnackbarException> { service.submit(STRANGER, CHALLENGE_ID, JPEG) }
        assertEquals(VerificationService.MSG_NOT_MINE, e.message)
    }

    /**
     * `IN_PROGRESS` 인데 row 가 없는 것은 데이터가 깨진 상태다. 조용히 INSERT 해서 덮으면
     * **"수락 없이 인증된 챌린지" 를 이 서비스가 만들어낼 수 있다.**
     */
    @Test
    fun `내 verification row 가 없으면 만들지 않고 거부한다`() {
        verifications.clear()

        val e = assertThrows<OneButtonDialogException> { service.submit(CHALLENGER, CHALLENGE_ID, JPEG) }

        assertEquals(VerificationService.MSG_VERIFICATION_MISSING, e.message)
        assertTrue(verifications.all().isEmpty())
        assertTrue(photos.stored.isEmpty(), "row 부재로 거부된 제출이 파일을 남기면 안 된다")
    }

    // ─── 알림 (T-B4) ─────────────────────────────────────────────

    /** spec 수용 기준: *"제출 시 상대에게 `OPPONENT_VERIFIED` 알림이 간다."* */
    @Test
    fun `제출하면 상대에게 OPPONENT_VERIFIED 이벤트가 발행된다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val event = events.challengeEvents().single()
        assertEquals(NotificationType.OPPONENT_VERIFIED, event.type)
        assertEquals(OPPONENT, event.recipientId, "알릴 대상은 **상대**다. 인증한 본인이 아니다")
        assertEquals(CHALLENGER, event.actorId)
        assertEquals(CHALLENGE_ID, event.challengeId)
    }

    /** 반대 방향도 같아야 한다 — opponent 가 인증하면 challenger 가 받는다. */
    @Test
    fun `상대가 인증하면 챌린저가 받는다`() {
        service.submit(OPPONENT, CHALLENGE_ID, JPEG)

        val event = events.challengeEvents().single()
        assertEquals(CHALLENGER, event.recipientId)
        assertEquals(OPPONENT, event.actorId)
    }

    // ─── 조회 (T-B3) ─────────────────────────────────────────────

    @Test
    fun `조회는 양측을 역할 그대로 준다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val view = service.getVerifications(OPPONENT, CHALLENGE_ID)

        assertEquals(CHALLENGER, view.challenger.userId)
        assertEquals(OPPONENT, view.opponent.userId)
        assertEquals(VerificationStatus.VERIFIED, view.challenger.status)
        assertEquals(VerificationStatus.PENDING, view.opponent.status)
    }

    /**
     * 🔴 `photoUrl` 은 **만료 없는 고정 상대 경로**다 (2026-08-18 2차 결정 — 조회 서명 URL 폐기).
     * 상대 경로 · 소문자 party · 쿼리 없음. 앱이 자기 baseUrl 에 붙여 쓴다.
     */
    @Test
    fun `VERIFIED 인 쪽만 만료 없는 상대 경로를 받는다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val view = service.getVerifications(OPPONENT, CHALLENGE_ID)

        assertEquals("/api/v1/challenges/$CHALLENGE_ID/photos/challenger", view.challenger.photoUrl)
        assertNotNull(view.challenger.verifiedAt)
        assertNull(view.opponent.photoUrl, "아직 안 올린 쪽에는 경로가 없다")
    }

    /** 반대쪽도 같은 규칙 — opponent 가 인증하면 opponent 경로가 나간다. */
    @Test
    fun `opponent 가 인증하면 opponent 경로가 나간다`() {
        service.submit(OPPONENT, CHALLENGE_ID, JPEG)

        val view = service.getVerifications(CHALLENGER, CHALLENGE_ID)

        assertEquals("/api/v1/challenges/$CHALLENGE_ID/photos/opponent", view.opponent.photoUrl)
        assertNull(view.challenger.photoUrl)
    }

    /** 🔴 spec 수용 기준: *"당사자가 아니면 사진을 볼 수 없다 — 제3자 요청은 거부된다."* */
    @Test
    fun `제3자는 조회할 수 없다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val e = assertThrows<SnackbarException> { service.getVerifications(STRANGER, CHALLENGE_ID) }

        assertEquals(VerificationService.MSG_NOT_MINE, e.message)
    }

    /**
     * 조회는 `IN_PROGRESS` 를 요구하지 않는다 — 챌린지가 끝난 뒤에도 *"그때 뭘 올렸는지"* 는
     * 보여야 한다. 판정 feature 가 `COMPLETED` 로 옮긴 뒤에도 상세 화면이 살아 있어야 한다.
     */
    @Test
    fun `끝난 챌린지도 조회할 수 있다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        challenges.put(challenge(ChallengeStatus.COMPLETED))

        val view = service.getVerifications(CHALLENGER, CHALLENGE_ID)

        assertEquals(VerificationStatus.VERIFIED, view.challenger.status)
        assertNotNull(view.challenger.photoUrl)
    }

    /** row 자체가 없으면 PENDING 으로 간주한다 (`Verification` KDoc). */
    @Test
    fun `row 가 없으면 양쪽 다 PENDING 으로 나간다`() {
        verifications.clear()
        challenges.put(challenge(ChallengeStatus.PENDING))

        val view = service.getVerifications(CHALLENGER, CHALLENGE_ID)

        assertEquals(VerificationStatus.PENDING, view.challenger.status)
        assertEquals(VerificationStatus.PENDING, view.opponent.status)
        assertNull(view.challenger.photoUrl)
    }

    // ─── photoDeleted (mypage T-B1 부록 A) ───────────────────────

    /**
     * 🔴 이 필드가 존재하는 이유 그 자체다.
     *
     * 탈퇴는 row(`status`/`verified_at`)를 보존한 채 사진 파일과 key 만 지운다 (mypage T-B2).
     * 그 결과 `VERIFIED` + `photoUrl = null` 이 나오는데, 앱은 그 조합을 이미
     * `"사진을 불러오지 못했어요"` 로 그리고 있다 — **영구 상태를 일시적 실패의 어휘로**
     * 말하게 된다. `photoDeleted` 가 둘을 가른다.
     */
    @Test
    fun `탈퇴자의 지워진 사진은 photoDeleted 가 true 다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        // T-B2 의 탈퇴 처리를 그대로 흉내낸다 — key 만 지우고 row 는 남긴다.
        verifications.clearPhotoKeysByUserId(CHALLENGER)
        users.put(user(CHALLENGER, UserStatus.DELETED))

        val view = service.getVerifications(OPPONENT, CHALLENGE_ID)

        assertTrue(view.challenger.photoDeleted, "탈퇴로 지워진 사진은 photoDeleted = true 여야 한다")
        // 기존 필드의 의미는 하나도 바뀌지 않는다 — 순수 추가다.
        assertEquals(VerificationStatus.VERIFIED, view.challenger.status)
        assertNull(view.challenger.photoUrl, "key 가 없으므로 경로도 없다")
    }

    @Test
    fun `사진이 살아 있으면 photoDeleted 가 false 다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val view = service.getVerifications(OPPONENT, CHALLENGE_ID)

        assertEquals(false, view.challenger.photoDeleted)
        assertNotNull(view.challenger.photoUrl)
    }

    /**
     * 🔴 **진짜 데이터 이상이 탈퇴자 뒤에 숨지 않는다는 것을 고정하는 테스트다.**
     *
     * `VERIFIED` 인데 key 가 없는데 owner 는 멀쩡히 살아 있다 = 탈퇴가 아니라 **버그**다.
     * 여기서 `true` 를 내보내면 앱이 *"탈퇴로 지워진 사진"* 으로 그려 버리고, 우리는
     * 그 이상을 영영 발견하지 못한다. 판정식에 `status == DELETED` 가 들어 있는 이유가 이것뿐이다.
     */
    @Test
    fun `VERIFIED 인데 key 가 없어도 owner 가 살아 있으면 photoDeleted 는 false 다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        verifications.clearPhotoKeysByUserId(CHALLENGER)
        // users 는 그대로 ACTIVE 다 — 탈퇴하지 않았는데 key 만 사라진 상태.

        val view = service.getVerifications(OPPONENT, CHALLENGE_ID)

        assertEquals(
            false,
            view.challenger.photoDeleted,
            "탈퇴가 아닌 key 부재는 데이터 이상이다 — 탈퇴자 뒤에 숨기면 영영 발견하지 못한다",
        )
    }

    /** 애초에 사진이 없었던 탈퇴자 — 지워진 것이 아니므로 `false` 다. */
    @Test
    fun `PENDING 인 탈퇴자는 photoDeleted 가 false 다`() {
        users.put(user(OPPONENT, UserStatus.DELETED))

        val view = service.getVerifications(CHALLENGER, CHALLENGE_ID)

        assertEquals(VerificationStatus.PENDING, view.opponent.status)
        assertEquals(false, view.opponent.photoDeleted, "올린 적이 없는 사진은 삭제된 것이 아니다")
    }

    // ─── 사진 읽기 (2026-08-18 2차 결정 — 조회 서명 URL 폐기) ────

    @Test
    fun `당사자는 상대 사진을 읽을 수 있다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val result = service.readPhoto(OPPONENT, CHALLENGE_ID, VerificationParty.CHALLENGER)

        assertTrue(result is PhotoReadResult.Found, "actual=$result")
        result as PhotoReadResult.Found
        assertArrayEquals(JPEG, result.bytes, "저장된 바이트가 그대로 돌아와야 한다")
        assertEquals("image/jpeg", result.contentType)
    }

    @Test
    fun `자기 사진도 읽을 수 있다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val result = service.readPhoto(CHALLENGER, CHALLENGE_ID, VerificationParty.CHALLENGER)

        assertEquals(PhotoReadResult.Found(JPEG, "image/jpeg"), result)
    }

    /**
     * 🔴 spec 수용 기준: *"당사자가 아니면 사진을 볼 수 없다."*
     *
     * 예외가 아니라 result 인 이유: 이 경로의 응답은 바이트라 `BusinessException` 을 던지면
     * `GlobalExceptionHandler` 가 **HTTP 200 + JSON** 으로 바꾸고, 앱 이미지 로더는 그 JSON 을
     * 이미지로 파싱하려다 깨진다. status 도 200 이라 실패를 감지할 수도 없다.
     */
    @Test
    fun `제3자 요청은 예외가 아니라 Forbidden 을 돌려준다 — BusinessException 이면 JSON 200 이 나간다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val result = service.readPhoto(STRANGER, CHALLENGE_ID, VerificationParty.CHALLENGER)

        assertEquals(PhotoReadResult.Forbidden, result)
    }

    @Test
    fun `없는 챌린지는 NotFound 다`() {
        assertEquals(PhotoReadResult.NotFound, service.readPhoto(CHALLENGER, 12345L, VerificationParty.CHALLENGER))
    }

    @Test
    fun `해당 party 가 아직 인증하지 않았으면 NotFound 다`() {
        // 아무도 제출하지 않았다 — 양쪽 다 PENDING.
        val result = service.readPhoto(CHALLENGER, CHALLENGE_ID, VerificationParty.OPPONENT)

        assertEquals(PhotoReadResult.NotFound, result)
    }

    /** key 는 row 에 남아 있는데 저장소에서 파일이 사라진 경우 — 500 이 아니라 404 다. */
    @Test
    fun `저장소에 파일이 없으면 NotFound 다`() {
        val saved = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        photos.stored.remove(saved.photoUrl)

        val result = service.readPhoto(OPPONENT, CHALLENGE_ID, VerificationParty.CHALLENGER)

        assertEquals(PhotoReadResult.NotFound, result)
    }

    /**
     * 읽기는 `IN_PROGRESS` 를 요구하지 않는다 — 조회([VerificationService.getVerifications])와
     * 같은 이유로, 끝난 챌린지의 사진도 보여야 한다.
     */
    @Test
    fun `끝난 챌린지의 사진도 읽을 수 있다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        challenges.put(challenge(ChallengeStatus.COMPLETED))

        val result = service.readPhoto(OPPONENT, CHALLENGE_ID, VerificationParty.CHALLENGER)

        assertEquals(PhotoReadResult.Found(JPEG, "image/jpeg"), result)
    }

    /**
     * 🔴 권한을 party 상태보다 먼저 본다 — `NotFound` 로 답하면 제3자가 *"아직 인증 안 했구나"*
     * 를 떠볼 수 있다 ([제3자에게는 챌린지 상태를 알려주지 않는다] 와 같은 축).
     */
    @Test
    fun `제3자에게는 인증 여부와 무관하게 Forbidden 이다`() {
        // 아무도 제출하지 않았다 — 당사자라면 NotFound 가 나올 상태.
        val result = service.readPhoto(STRANGER, CHALLENGE_ID, VerificationParty.CHALLENGER)

        assertEquals(PhotoReadResult.Forbidden, result)
    }
}

// ─── in-memory fakes ─────────────────────────────────────────────

/**
 * 바이트를 담아 두는 [PhotoStorage].
 *
 * ⚠️ 파일시스템은 흉내내지 않는다 — 그쪽은 `LocalDiskPhotoStorageTest` 가 실제 디스크로
 * 검증한다. 여기서 알 수 있는 것은 **서비스가 무엇을 저장하고 무엇을 읽는가** 뿐이다.
 * [stored] 를 그대로 노출하는 이유: 거부 케이스에서 *"저장이 일어나지 않았다"* 를 단언하고,
 * `readPhoto` 의 *"key 는 있는데 파일이 없다"* 케이스를 만들어야 한다.
 */
private class FakePhotoStorage : PhotoStorage {
    /** key → 저장된 바이트. */
    val stored = LinkedHashMap<String, ByteArray>()
    private var sequence = 0

    override fun store(challengeId: Long, userId: Long, bytes: ByteArray): String {
        val key = "$challengeId/$userId/%032x.jpg".format(++sequence)
        stored[key] = bytes.copyOf()
        return key
    }

    override fun read(key: String): ByteArray? = stored[key]?.copyOf()

    override fun delete(key: String) {
        stored -= key
    }
}

private class FakeChallengeRepository : ChallengeRepository {
    private val store = LinkedHashMap<Long, Challenge>()

    fun put(c: Challenge) {
        store[c.id!!] = c
    }

    override fun findById(id: Long): Challenge? = store[id]

    override fun findActiveByUser(userId: Long, statuses: Collection<ChallengeStatus>): List<Challenge> =
        store.values.filter { (it.challengerId == userId || it.opponentId == userId) && it.status in statuses }

    override fun findReceivedPending(opponentId: Long, now: LocalDateTime): List<ReceivedChallenge> = emptyList()

    /** 홈 목록 전용 — 이 테스트가 다루는 경로가 아니다 (`ActiveChallengeServiceTest` 소관). */
    override fun findRecentlyCompletedByUser(userId: Long, since: LocalDateTime): List<Challenge> = emptyList()

    /** 보관함 전용 — 이 테스트가 다루는 경로가 아니다 (`ChallengeHistoryServiceTest` 소관). */
    override fun findCompletedHistoryByUserInMonth(
        userId: Long,
        monthStart: LocalDate,
        nextMonthStart: LocalDate,
    ): List<Challenge> = emptyList()

    override fun findEarliestCompletedChallengeDate(userId: Long): LocalDate? = null

    /** 판정 배치 전용 — 이 테스트가 다루는 경로가 아니다 (`ChallengeJudgementRunnerTest` 소관). */
    override fun findDueByStatus(statuses: Collection<ChallengeStatus>, now: LocalDateTime): List<Challenge> =
        emptyList()

    override fun findConflicting(
        userA: Long,
        userB: Long,
        challengeDate: LocalDate,
        statuses: Collection<ChallengeStatus>,
    ): List<Challenge> = emptyList()

    override fun save(challenge: Challenge): Challenge = challenge.also { put(it) }
    override fun saveAndFlush(challenge: Challenge): Challenge = save(challenge)
    override fun deleteById(id: Long) {
        store.remove(id)
    }

    /** 탈퇴 전용 — 이 테스트가 다루는 경로가 아니다 (`WithdrawalServiceTest` 소관). */
    override fun deletePendingSentBy(userId: Long): Int = 0
}

private class FakeVerificationRepository : VerificationRepository {
    private val store = mutableListOf<Verification>()
    private var nextId = 1L

    fun all(): List<Verification> = store.toList()
    fun clear() = store.clear()

    override fun findAllByChallengeIds(challengeIds: Collection<Long>): List<Verification> =
        store.filter { it.challengeId in challengeIds }

    override fun save(verification: Verification): Verification {
        val saved = verification.copy(id = verification.id ?: nextId++)
        store.removeAll { it.id == saved.id }
        store.add(saved)
        return saved
    }

    override fun saveAllAndFlush(verifications: Collection<Verification>): List<Verification> =
        verifications.map { save(it) }

    override fun findAllByUserId(userId: Long): List<Verification> =
        store.filter { it.userId == userId }

    override fun clearPhotoKeysByUserId(userId: Long): Int {
        val targets = store.filter { it.userId == userId && it.photoUrl != null }
        targets.forEach { target ->
            store.removeAll { it.id == target.id }
            store.add(target.copy(photoUrl = null))
        }
        return targets.size
    }
}

/**
 * `photoDeleted` 판정에 필요한 **계정 상태**만 들고 있는 [UserRepository].
 *
 * 조회 경로가 쓰는 것은 [findAllByIds] 하나뿐이다. 나머지 메서드는 이 서비스가 부르면
 * 그 자체가 버그이므로 [error] 로 못박는다 — 조용히 no-op 을 돌려주면 잘못된 호출이
 * 테스트를 통과해 버린다.
 */
private class FakeUserStore : UserRepository {
    private val store = LinkedHashMap<Long, User>()

    fun put(u: User) {
        store[u.id!!] = u
    }

    override fun findAllByIds(ids: Collection<Long>): Map<Long, User> =
        ids.mapNotNull { store[it] }.associateBy { it.id!! }

    override fun findById(id: Long): User? = store[id]

    override fun findByKakaoId(kakaoId: Long): User? = store.values.firstOrNull { it.kakaoId == kakaoId }

    override fun save(user: User): User = error("인증 서비스가 user 를 저장하려 했다")

    override fun updateRefreshTokenHash(userId: Long, hash: String?, issuedAt: LocalDateTime?) =
        error("인증 서비스가 토큰을 건드리려 했다")

    override fun clearFcmTokenOwnedByOthers(fcmToken: String, exceptUserId: Long): Int =
        error("인증 서비스가 FCM 토큰을 건드리려 했다")

    override fun updateFcmToken(userId: Long, fcmToken: String?): Int =
        error("인증 서비스가 FCM 토큰을 건드리려 했다")

    override fun anonymizeForWithdrawal(userId: Long, anonymizedNickname: String, now: LocalDateTime): Int =
        error("인증 서비스가 익명화를 시도했다")
}

/** ⚠️ `AFTER_COMMIT` 의미를 흉내내지 않는다 — 발행 즉시 기록한다. */
private class RecordingEventPublisher : ApplicationEventPublisher {
    private val published = mutableListOf<Any>()

    override fun publishEvent(event: Any) {
        published += event
    }

    fun challengeEvents(): List<ChallengeNotificationEvent> = published.filterIsInstance<ChallengeNotificationEvent>()
    fun clear() = published.clear()
}
