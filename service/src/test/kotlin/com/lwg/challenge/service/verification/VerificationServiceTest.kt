package com.lwg.challenge.service.verification

import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeResult
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
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [VerificationService] 단위 테스트 (challenge-verification T-B2·T-B3·T-B4).
 *
 * 커버하는 spec 수용 기준:
 * - 제출 후 `status = VERIFIED`, `photo_url` 에 저장소 key 채워짐, `verified_at` 기록됨
 * - 제출 시 상대에게 `OPPONENT_VERIFIED` 알림이 간다
 * - 🔴 당사자가 아니면 사진을 볼 수 없다 — 제3자 요청은 거부된다
 * - 같은 사용자가 두 번 제출해도 row 가 하나다
 * - `IN_PROGRESS` 가 아닌 챌린지에는 제출할 수 없다
 * - 🔴 `photoDeleted` 가 **탈퇴로 지워진 사진과 진짜 데이터 이상을 가른다** (mypage T-B1 부록 A)
 *
 * verification-photo-replace(2026-09-02) 가 더한 것:
 * - 🔴 재제출은 **`last-write-wins`** 다 — 사진이 교체되고 **이전 파일이 지워진다**
 * - 🔴 `OPPONENT_VERIFIED` 는 **최초 1회만** — 3회 제출해도 이벤트는 1건이다
 * - 🔴 `verified_at` 이 **최종 교체 시각**으로 갱신된다
 * - 🔴 **거부 검사는 교체 경로에도 그대로 산다** — 당사자·`IN_PROGRESS`·JPEG·빈 바이트
 *
 * ⚠️ **덮지 못하는 것**: `AFTER_COMMIT` 타이밍. 이 테스트에는 트랜잭션이 없어 알 수 있는 것은
 * *무엇을 발행했는가* 뿐이다 (`NotificationDispatcherTest` 와 같은 한계). 사진 삭제가 커밋
 * **뒤**라는 것도 시점으로는 못 본다 — 대신 *"[VerificationSubmitTransactionalWorker] 는 지우지
 * 않는다"* 와 *"[VerificationService.submit] 에 `@Transactional` 이 없다"* 를 구조로 고정한다.
 */
class VerificationServiceTest {

    private val challenges = FakeChallengeRepository()
    private val verifications = FakeVerificationRepository()
    private val users = FakeUserStore()
    private val photos = FakePhotoStorage()
    private val events = RecordingEventPublisher()

    /**
     * 🔴 `Clock.fixed` 가 아니다 — 교체가 `verified_at` 을 **갱신**하는지 보려면 두 제출의
     * 시각이 달라야 한다. 고정 시계로는 *"갱신했다"* 와 *"안 건드렸다"* 가 구분되지 않는다.
     * 기본값은 예전 `Clock.fixed` 와 같은 순간이라 나머지 테스트의 단언은 그대로다.
     */
    private val clock = MutableClock(Instant.parse("2026-08-14T06:32:10Z"), KstTime.ZONE)

    private val submitWorker = VerificationSubmitTransactionalWorker(challenges, verifications, photos, clock, events)
    private val service = VerificationService(challenges, verifications, users, photos, submitWorker)

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
        forfeitedBy = null,
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

    // ─── 재제출 정책 (계약 §3 — last-write-wins, 2026-09-02 개정) ─

    /**
     * 🔴 spec 수용 기준: *"`VERIFIED` 상태에서 재제출하면 거부되지 않고 사진이 교체된다."*
     *
     * 뒤집은 근거는 *"판별 수단이 생겼다"* 가 아니라 **전제 소멸**이다 — 옛 정책이 막으려던 것은
     * *"올린 사진이 조용히 버려진다"* 였는데 `last-write-wins` 는 올린 사진을 **저장한다.**
     */
    @Test
    fun `재제출하면 사진이 교체되고 마지막 사진이 최종본이다`() {
        val replacement = JPEG + 0x33.toByte()

        val first = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        val second = service.submit(CHALLENGER, CHALLENGE_ID, replacement)

        assertEquals(VerificationStatus.VERIFIED, second.status)
        assertTrue(second.photoUrl != first.photoUrl, "교체는 새 key 를 만든다: ${second.photoUrl}")
        assertArrayEquals(replacement, photos.stored[second.photoUrl!!], "마지막에 올린 바이트가 최종본이다")
        val mine = verifications.all().single { it.userId == CHALLENGER }
        assertEquals(second.photoUrl, mine.photoUrl, "조회가 보는 것도 마지막 사진이어야 한다")
    }

    /**
     * 🔴 spec 수용 기준: *"이전 사진 파일이 삭제된다 — 참조 0인 파일이 디스크에 쌓이지 않는다."*
     */
    @Test
    fun `재제출하면 이전 사진 파일이 삭제된다`() {
        val first = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val second = service.submit(CHALLENGER, CHALLENGE_ID, JPEG + 0x33.toByte())

        assertEquals(listOf(first.photoUrl), photos.deleted, "참조가 끊긴 이전 key 를 지워야 한다")
        assertEquals(setOf(second.photoUrl), photos.stored.keys, "저장소에는 최종본 하나만 남는다")
    }

    /** 첫 제출은 지울 것이 없다 — 없는 key 를 지우러 가면 안 된다. */
    @Test
    fun `첫 제출은 아무것도 삭제하지 않는다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        assertTrue(photos.deleted.isEmpty(), "actual=${photos.deleted}")
    }

    /**
     * 🔴 **삭제는 커밋 후여야 한다** ([PhotoStorage.delete] KDoc — 롤백돼도 파일은 안 돌아온다).
     *
     * 단위 테스트에는 트랜잭션이 없어 *시점* 은 볼 수 없다. 대신 **트랜잭션 경계 안쪽인
     * [VerificationSubmitTransactionalWorker] 가 삭제를 하지 않는다**는 것을 고정한다 —
     * 누군가 편의상 worker 안에서 지우면 이 테스트가 깨진다.
     */
    @Test
    fun `트랜잭션 경계 안에서는 이전 파일을 지우지 않고 key 만 돌려준다`() {
        val first = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        photos.deleted.clear()

        val outcome = submitWorker.submit(CHALLENGER, CHALLENGE_ID, JPEG + 0x33.toByte())

        assertEquals(first.photoUrl, outcome.replacedPhotoKey, "지울 key 를 호출자에게 넘겨야 한다")
        assertTrue(photos.deleted.isEmpty(), "커밋 전에 지우면 롤백 시 파일이 돌아오지 않는다")
        assertTrue(photos.stored.containsKey(first.photoUrl), "이전 파일이 트랜잭션 안에서 사라지면 안 된다")
    }

    /**
     * 🔴 **`@Transactional` 의 위치가 이 구조의 전부다.**
     *
     * [VerificationService.submit] 에 붙으면 커밋이 그 메서드가 끝날 때까지 미뤄져 **삭제가 커밋
     * 전으로 당겨진다.** worker 에서 빠지면 파일 저장과 row 저장이 따로 커밋된다.
     * 둘 다 컴파일되고 다른 테스트는 전부 통과하므로 여기서 못박는다
     * (`NotificationDispatcherWiringTest` 와 같은 방식).
     */
    @Test
    fun `submit 의 트랜잭션 경계는 worker 에만 있다`() {
        val outer = VerificationService::class.java
            .getDeclaredMethod("submit", Long::class.java, Long::class.java, ByteArray::class.java)
        val inner = VerificationSubmitTransactionalWorker::class.java
            .getDeclaredMethod("submit", Long::class.java, Long::class.java, ByteArray::class.java)

        assertNull(
            outer.getAnnotation(Transactional::class.java),
            "VerificationService.submit 에 @Transactional 이 붙으면 이전 파일 삭제가 커밋 전에 실행된다",
        )
        assertNotNull(
            inner.getAnnotation(Transactional::class.java),
            "worker 에서 @Transactional 이 빠지면 파일 저장과 row 저장이 따로 커밋된다",
        )
    }

    /**
     * 🔴 spec 수용 기준: *"`OPPONENT_VERIFIED` 알림은 최초 1회만 — 3회 제출 시 row 증가가 1."*
     *
     * 교체할 때마다 보내면 사진을 다듬는 동안 상대의 알림함이 같은 문구로 채워진다.
     */
    @Test
    fun `세 번 제출해도 상대에게 가는 알림은 한 건이다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG + 0x33.toByte())
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG + 0x44.toByte())

        assertEquals(1, events.challengeEvents().size, "교체는 알리지 않는다: ${events.challengeEvents()}")
        assertEquals(NotificationType.OPPONENT_VERIFIED, events.challengeEvents().single().type)
    }

    /** 🔴 spec 수용 기준: *"`verified_at` 이 최종 교체 시각으로 갱신된다."* */
    @Test
    fun `재제출하면 verified_at 이 최종 교체 시각으로 갱신된다`() {
        val first = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        clock.advanceMinutes(37)

        val second = service.submit(CHALLENGER, CHALLENGE_ID, JPEG + 0x33.toByte())

        assertEquals(LocalDateTime.now(clock), second.verifiedAt)
        assertTrue(second.verifiedAt!! > first.verifiedAt!!, "${second.verifiedAt} 가 ${first.verifiedAt} 보다 뒤여야 한다")
    }

    /** 교체도 있는 row 를 채운다 — `uq_verifications_challenge_user` 는 여전히 하나만 허용한다. */
    @Test
    fun `재제출해도 row 는 여전히 하나다`() {
        val first = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        val second = service.submit(CHALLENGER, CHALLENGE_ID, JPEG + 0x33.toByte())

        assertEquals(first.id, second.id)
        assertEquals(2, verifications.all().size, "row 수가 늘면 uq_verifications_challenge_user 가 터진다")
    }

    /**
     * 🔴 **거부 하나를 지웠다고 나머지 검사까지 흘려보내지 않는다.** 교체 경로도 JPEG 검사를
     * 지나야 하고, 거부되면 **이전 사진은 그대로 살아 있어야 한다.**
     */
    @Test
    fun `교체 요청도 JPEG 검사를 지나고 거부되면 이전 사진이 살아 있다`() {
        val first = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val e = assertThrows<SnackbarException> { service.submit(CHALLENGER, CHALLENGE_ID, PNG) }

        assertEquals(VerificationService.MSG_NOT_JPEG, e.message)
        assertEquals(setOf(first.photoUrl), photos.stored.keys, "거부된 교체가 파일을 만들거나 지우면 안 된다")
        assertTrue(photos.deleted.isEmpty())
        assertEquals(first.photoUrl, verifications.all().single { it.userId == CHALLENGER }.photoUrl)
    }

    @Test
    fun `교체 요청도 빈 바이트를 거부하고 이전 사진이 살아 있다`() {
        val first = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val e = assertThrows<SnackbarException> { service.submit(CHALLENGER, CHALLENGE_ID, ByteArray(0)) }

        assertEquals(VerificationService.MSG_PHOTO_REQUIRED, e.message)
        assertEquals(setOf(first.photoUrl), photos.stored.keys)
        assertTrue(photos.deleted.isEmpty())
    }

    /**
     * 🔴 spec 수용 기준: *"`IN_PROGRESS` 가 아니면 여전히 거부된다 (마감 후 교체 불가)."*
     *
     * 증거 보존은 여기에 걸려 있다 — *"마감 시점의 사진이 최종본이고 그 뒤로 불변"*.
     */
    @Test
    fun `마감 후에는 교체할 수 없고 이전 사진이 그대로 남는다`() {
        val first = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        challenges.put(challenge(ChallengeStatus.COMPLETED))

        val e = assertThrows<OneButtonDialogException> { service.submit(CHALLENGER, CHALLENGE_ID, JPEG + 0x33.toByte()) }

        assertEquals(VerificationService.MSG_NOT_IN_PROGRESS, e.message)
        assertEquals(setOf(first.photoUrl), photos.stored.keys)
        assertTrue(photos.deleted.isEmpty())
    }

    /** 제3자가 남의 인증 사진을 교체할 수 없다 — 당사자 검사도 교체 경로에 그대로 산다. */
    @Test
    fun `제3자는 남의 인증 사진을 교체할 수 없다`() {
        val first = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val e = assertThrows<SnackbarException> { service.submit(STRANGER, CHALLENGE_ID, JPEG + 0x33.toByte()) }

        assertEquals(VerificationService.MSG_NOT_MINE, e.message)
        assertEquals(setOf(first.photoUrl), photos.stored.keys)
        assertTrue(photos.deleted.isEmpty())
    }

    /**
     * 🔴 삭제 실패는 **삼킨다.** 여기서 예외가 새면 이미 커밋된 교체가 실패로 응답되고,
     * 사용자는 사진이 안 바뀐 줄 알고 다시 올린다 — 그때마다 고아가 하나씩 더 는다.
     * 남는 것은 아무 row 도 가리키지 않는 파일 하나뿐이다 (spec §6).
     */
    @Test
    fun `이전 파일 삭제가 실패해도 교체는 성공한다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        photos.failDelete = true

        val second = service.submit(CHALLENGER, CHALLENGE_ID, JPEG + 0x33.toByte())

        assertEquals(VerificationStatus.VERIFIED, second.status)
        assertEquals(second.photoUrl, verifications.all().single { it.userId == CHALLENGER }.photoUrl)
    }

    /**
     * 탈퇴가 `photo_url` 만 NULL 로 밀어 둔 row 에 다시 제출하는 경우 — 지울 key 가 **없다.**
     * 교체 판정을 `status` 로 했다면 여기서 null 을 지우러 갔을 것이다.
     */
    @Test
    fun `key 가 없는 VERIFIED row 에 제출해도 삭제를 시도하지 않는다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        verifications.clearPhotoKeysByUserId(CHALLENGER)
        photos.stored.clear()

        service.submit(CHALLENGER, CHALLENGE_ID, JPEG + 0x33.toByte())

        assertTrue(photos.deleted.isEmpty(), "지울 key 가 없으면 삭제를 부르지 않는다: ${photos.deleted}")
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
     * 🔴 **상대가 기권한 뒤에는 내가 인증을 올릴 수 없다** (challenge-forfeit spec §3).
     *
     * spec §3.1 이 이 항목을 *"저절로 닫히는 것"* 으로 분류하고 **별도 가드를 추가하지 말라**고
     * 못박았다. 근거는 기권이 챌린지를 즉시 `COMPLETED` 로 만들고, 제출이 **이미**
     * `IN_PROGRESS` 를 요구한다는 것이다. 이 테스트는 **그 근거가 실제로 성립하는지**를 본다 —
     * 근거만 적어 두고 확인하지 않으면, 나중에 기권이 다른 상태를 쓰게 되는 날 조용히 뚫린다.
     *
     * ⚠️ 위 [IN_PROGRESS 가 아니면 제출할 수 없다] 와 겹쳐 보이지만 축이 다르다. 그쪽은
     * *"상태 목록"* 을 고정하고, 이쪽은 *"기권이 만드는 상태가 그 목록 안에 있다"* 를 고정한다.
     */
    @Test
    fun `상대가 기권해 끝난 챌린지에는 인증을 제출할 수 없다`() {
        // 기권이 남기는 상태: COMPLETED + result + forfeited_by. 사진은 한 장도 저장되지 않는다.
        challenges.put(
            challenge(ChallengeStatus.COMPLETED).copy(
                result = ChallengeResult.CHALLENGER_WIN,
                forfeitedBy = OPPONENT,
            ),
        )

        val e = assertThrows<OneButtonDialogException> { service.submit(CHALLENGER, CHALLENGE_ID, JPEG) }

        assertEquals(VerificationService.MSG_NOT_IN_PROGRESS, e.message)
        assertTrue(photos.stored.isEmpty(), "거부된 요청은 디스크에 아무것도 남기지 않는다")
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

        assertEquals(PhotoReadResult.Found(JPEG, "image/jpeg", etagOf(result)), result)
    }

    /**
     * 🔴 사진 교체가 열리면서 **같은 경로가 다른 바이트를 낸다.** ETag 가 바뀌지 않으면 앱이
     * 304 를 받고 옛 사진을 계속 보여준다 — 에러도 로그도 남지 않는 조용한 실패다.
     */
    @Test
    fun `사진을 교체하면 ETag 가 바뀐다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)
        val before = etagOf(service.readPhoto(OPPONENT, CHALLENGE_ID, VerificationParty.CHALLENGER))

        service.submit(CHALLENGER, CHALLENGE_ID, JPEG + 0x33.toByte())
        val after = etagOf(service.readPhoto(OPPONENT, CHALLENGE_ID, VerificationParty.CHALLENGER))

        assertTrue(before != after, "교체 전후의 ETag 가 같으면 앱이 옛 사진을 계속 본다: $before")
    }

    /** 안 바뀌었으면 안 바뀌어야 한다 — 그래야 304 로 재진입이 싸다. */
    @Test
    fun `사진이 그대로면 ETag 도 그대로다`() {
        service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val first = etagOf(service.readPhoto(OPPONENT, CHALLENGE_ID, VerificationParty.CHALLENGER))
        val second = etagOf(service.readPhoto(OPPONENT, CHALLENGE_ID, VerificationParty.CHALLENGER))

        assertEquals(first, second)
    }

    /** 🔴 계약 §0.4 — 저장소 key 는 앱에 노출되지 않는다. ETag 는 응답 헤더라 여기가 새는 자리다. */
    @Test
    fun `ETag 에 저장소 key 가 그대로 실리지 않는다`() {
        val saved = service.submit(CHALLENGER, CHALLENGE_ID, JPEG)

        val etag = etagOf(service.readPhoto(OPPONENT, CHALLENGE_ID, VerificationParty.CHALLENGER))

        assertTrue(!etag.contains(saved.photoUrl!!), "key 가 ETag 로 새 나갔다: $etag")
        assertTrue(etag.startsWith("\"") && etag.endsWith("\""), "ETag 는 큰따옴표로 감싼 값이다: $etag")
    }

    private fun etagOf(result: PhotoReadResult): String {
        assertTrue(result is PhotoReadResult.Found, "actual=$result")
        return (result as PhotoReadResult.Found).etag
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

        assertEquals(PhotoReadResult.Found(JPEG, "image/jpeg", etagOf(result)), result)
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

    /** 삭제된 key 를 **순서대로** 기록한다 — 교체가 지운 것이 이전 key 하나임을 단언하려고 있다. */
    val deleted = mutableListOf<String>()

    /** `true` 면 [delete] 가 던진다. 삭제 실패가 교체를 실패시키지 않는지 보는 데 쓴다. */
    var failDelete = false

    override fun read(key: String): ByteArray? = stored[key]?.copyOf()

    override fun delete(key: String) {
        if (failDelete) throw IllegalStateException("삭제 실패 시뮬레이션: $key")
        deleted += key
        stored -= key
    }
}

/**
 * 앞으로 흐를 수 있는 [Clock].
 *
 * `Clock.fixed` 로는 *"교체가 `verified_at` 을 갱신했다"* 와 *"안 건드렸다"* 가 같은 값을 내서
 * 구분되지 않는다. 기본 동작은 고정 시계와 같고, [advanceMinutes] 를 부른 테스트만 달라진다.
 */
private class MutableClock(private var current: Instant, private val zone: ZoneId) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
    override fun instant(): Instant = current

    fun advanceMinutes(minutes: Long) {
        current = current.plusSeconds(minutes * 60)
    }
}

private class FakeChallengeRepository : ChallengeRepository {
    private val store = LinkedHashMap<Long, Challenge>()

    fun put(c: Challenge) {
        store[c.id!!] = c
    }

    override fun findById(id: Long): Challenge? = store[id]
    /** 잠금은 흉내낼 수 없다 — 이 fake 로는 동시성이 검증되지 않는다 (JudgementFakes KDoc). */
    override fun findByIdForUpdate(id: Long): Challenge? = findById(id)

    override fun findActiveByUser(userId: Long, statuses: Collection<ChallengeStatus>): List<Challenge> =
        store.values.filter { (it.challengerId == userId || it.opponentId == userId) && it.status in statuses }

    override fun findReceivedPending(opponentId: Long, now: LocalDateTime): List<ReceivedChallenge> = emptyList()

    /** 알림 목록 전용 — 이 테스트가 다루는 경로가 아니다 (`NotificationQueryServiceTest` 소관). */
    override fun findPendingReceivedIdsIn(
        opponentId: Long,
        ids: Collection<Long>,
        now: LocalDateTime,
    ): Set<Long> = error("인증 경로는 응답 대기 판정을 하지 않는다")

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
