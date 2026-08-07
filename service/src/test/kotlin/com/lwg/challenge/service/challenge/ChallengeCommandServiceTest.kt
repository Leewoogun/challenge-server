package com.lwg.challenge.service.challenge

import com.lwg.challenge.core.challenge.DeadlineType
import com.lwg.challenge.core.challenge.KstDeadlineCalculator
import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.ReceivedChallenge
import com.lwg.challenge.domain.contract.Contract
import com.lwg.challenge.domain.contract.ContractRepository
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.BusinessException
import com.lwg.challenge.domain.friend.Friend
import com.lwg.challenge.domain.friend.FriendRequest
import com.lwg.challenge.domain.friend.Friendship
import com.lwg.challenge.domain.friend.FriendshipRepository
import com.lwg.challenge.domain.friend.FriendshipStatus
import com.lwg.challenge.domain.friend.UserSearchResult
import com.lwg.challenge.domain.notification.NotificationType
import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.user.UserStatus
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationRepository
import com.lwg.challenge.domain.verification.VerificationStatus
import com.lwg.challenge.service.notification.ChallengeNotificationEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import com.lwg.challenge.core.time.KstTime

/**
 * ChallengeCommandService 단위 테스트 (challenge-create T-B4).
 *
 * Spring 컨텍스트 / DB 없이 in-memory fake repository 로 검증한다. Mockito 대신 fake 를 쓰는 이유:
 * 이 서비스의 핵심은 "여러 저장소에 걸친 상태 전이" 라서, 호출 검증(verify)보다 **저장된 결과**를
 * 그대로 읽어 확인하는 편이 회귀를 훨씬 잘 잡는다 (수락 시 verifications 2건 같은 것).
 *
 * 시각은 `Clock.fixed` 로 고정 — 자정 경계·마감 경과 분기를 결정적으로 재현하기 위함.
 *
 * 커버 범위: KST 자정 환산 반영, 양방향 중복 판정(+메시지 분기), 권한, 상태, 마감, 길이/trim,
 * 수락 시 verifications 2건 생성, 취소 물리 삭제.
 */
class ChallengeCommandServiceTest {

    private companion object {
        /** 유효한 최소 서명 — 획 1개, 점 2개. */
        const val VALID_SIGNATURE = """{"v":1,"g":1000,"s":[[100,200,110,210]]}"""
    }

    // KST 2026-07-28 14:00. ADR-0010 이후 clock 자체가 KST 라 벽시계 = 저장값이다.
    private val fixedNow: Instant = Instant.parse("2026-07-28T05:00:00Z") // = KST 14:00
    private val clock: Clock = Clock.fixed(fixedNow, KstTime.ZONE)

    private val me = 1L
    private val friend = 2L
    private val stranger = 3L

    // ─── fakes ───────────────────────────────────────────────────

    private val challenges = FakeChallengeRepository()
    private val friendships = FakeFriendshipRepository()
    private val users = FakeUserRepository()
    private val verifications = FakeVerificationRepository()
    private val contracts = FakeContractRepository()

    /**
     * 발행된 알림 이벤트를 모아 두는 fake (push-fcm T-B5).
     *
     * ⚠️ **여기서 볼 수 있는 것은 "무엇을 발행했는가" 뿐이다.** `publishEvent` 를 그대로 기록할 뿐,
     * *커밋된 뒤에만 리스너가 돈다* 는 보장은 Spring 의 `@TransactionalEventListener(AFTER_COMMIT)`
     * 이 하며 이 테스트는 트랜잭션 없이 돈다. 즉 **"롤백 시 미발송"을 이 파일이 증명하지는 않는다.**
     */
    private val events = RecordingEventPublisher()

    private fun service(clock: Clock = this.clock) = ChallengeCommandService(
        challengeRepository = challenges,
        friendshipRepository = friendships,
        userRepository = users,
        verificationRepository = verifications,
        contractRepository = contracts,
        clock = clock,
        eventPublisher = events,
    )

    private fun seedUsersAndFriendship() {
        users.put(user(me, "나"))
        users.put(user(friend, "민수"))
        users.put(user(stranger, "남남"))
        friendships.put(
            Friendship(
                id = 100L,
                requesterId = me,
                receiverId = friend,
                status = FriendshipStatus.ACCEPTED,
                createdAt = LocalDateTime.of(2026, 7, 1, 0, 0),
                acceptedAt = LocalDateTime.of(2026, 7, 1, 0, 0),
            ),
        )
    }

    // ─── create: 정상 ────────────────────────────────────────────

    @Test
    fun `create - KST 자정 환산이 반영되고 PENDING + opponentMission null 로 생성된다`() {
        seedUsersAndFriendship()

        val created = service().createSigned(me, friend, "운동 1시간", "커피 사기", DeadlineType.TODAY)

        assertEquals(ChallengeStatus.PENDING, created.status)
        assertNull(created.opponentMission, "생성 직후 opponentMission 은 null 이어야 한다")
        assertEquals(LocalDate.of(2026, 7, 28), created.challengeDate)
        // ADR-0010: deadline 은 KST 벽시계 그대로 — 07-29 00:00 (예전에는 07-28T15:00Z 로 저장됐다)
        assertEquals(LocalDateTime.of(2026, 7, 29, 0, 0), created.deadline)
        assertEquals(me, created.challengerId)
        assertEquals(friend, created.opponentId)
        assertNull(created.result)
    }

    @Test
    fun `create - TOMORROW 는 challengeDate 가 내일이다`() {
        seedUsersAndFriendship()

        val created = service().createSigned(me, friend, "운동", "커피", DeadlineType.TOMORROW)

        assertEquals(LocalDate.of(2026, 7, 29), created.challengeDate)
        assertEquals(LocalDateTime.of(2026, 7, 30, 0, 0), created.deadline)
    }

    @Test
    fun `create - 미션과 내기는 trim 되어 저장된다`() {
        seedUsersAndFriendship()

        val created = service().createSigned(me, friend, "  운동 1시간  ", "\t커피 사기\n", DeadlineType.TODAY)

        assertEquals("운동 1시간", created.challengerMission)
        assertEquals("커피 사기", created.betContent)
    }

    // ─── create: 검증 실패 ───────────────────────────────────────

    @Test
    fun `create - 본인에게는 걸 수 없다`() {
        seedUsersAndFriendship()

        val e = assertThrows<BusinessException> {
            service().createSigned(me, me, "운동", "커피", DeadlineType.TODAY)
        }
        assertEquals(ResponseCode.SNACKBAR_ERROR, e.code)
        assertEquals(ChallengeCommandService.MSG_SELF_CHALLENGE, e.message)
    }

    @Test
    fun `create - 친구가 아니면 거부된다`() {
        seedUsersAndFriendship()

        val e = assertThrows<BusinessException> {
            service().createSigned(me, stranger, "운동", "커피", DeadlineType.TODAY)
        }
        assertEquals(ResponseCode.SNACKBAR_ERROR, e.code)
        assertEquals(ChallengeCommandService.MSG_NOT_FRIEND, e.message)
    }

    @Test
    fun `create - PENDING 친구요청 상태는 친구가 아니다`() {
        users.put(user(me, "나"))
        users.put(user(friend, "민수"))
        friendships.put(
            Friendship(
                id = 101L,
                requesterId = me,
                receiverId = friend,
                status = FriendshipStatus.PENDING,
                createdAt = LocalDateTime.of(2026, 7, 1, 0, 0),
                acceptedAt = null,
            ),
        )

        val e = assertThrows<BusinessException> {
            service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY)
        }
        assertEquals(ChallengeCommandService.MSG_NOT_FRIEND, e.message)
    }

    @Test
    fun `create - 존재하지 않는 상대는 거부된다`() {
        seedUsersAndFriendship()

        val e = assertThrows<BusinessException> {
            service().createSigned(me, 999L, "운동", "커피", DeadlineType.TODAY)
        }
        assertEquals(ChallengeCommandService.MSG_OPPONENT_NOT_FOUND, e.message)
    }

    @Test
    fun `create - 탈퇴한 상대는 존재하지 않는 것으로 취급한다`() {
        seedUsersAndFriendship()
        users.put(user(friend, "민수", UserStatus.DELETED))

        val e = assertThrows<BusinessException> {
            service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY)
        }
        assertEquals(ChallengeCommandService.MSG_OPPONENT_NOT_FOUND, e.message)
    }

    @Test
    fun `create - 미션이 공백뿐이면 거부된다`() {
        seedUsersAndFriendship()

        val e = assertThrows<BusinessException> {
            service().createSigned(me, friend, "   ", "커피", DeadlineType.TODAY)
        }
        assertEquals(ChallengeCommandService.MSG_MISSION_LENGTH, e.message)
    }

    @Test
    fun `create - 미션 100자는 통과하고 101자는 거부된다`() {
        seedUsersAndFriendship()

        val ok = service().createSigned(me, friend, "가".repeat(100), "커피", DeadlineType.TODAY)
        assertEquals(100, ok.challengerMission.length)

        challenges.clear()
        val e = assertThrows<BusinessException> {
            service().createSigned(me, friend, "가".repeat(101), "커피", DeadlineType.TODAY)
        }
        assertEquals(ChallengeCommandService.MSG_MISSION_LENGTH, e.message)
    }

    @Test
    fun `create - 내기 길이 위반은 미션과 다른 문구다`() {
        seedUsersAndFriendship()

        val e = assertThrows<BusinessException> {
            service().createSigned(me, friend, "운동", "", DeadlineType.TODAY)
        }
        assertEquals(ChallengeCommandService.MSG_BET_LENGTH, e.message)
    }

    @Test
    fun `create - trim 후 길이를 재므로 공백 패딩된 100자는 통과한다`() {
        seedUsersAndFriendship()

        val created = service().createSigned(me, friend, "  " + "가".repeat(100) + "  ", "커피", DeadlineType.TODAY)

        assertEquals(100, created.challengerMission.length)
    }

    // ─── create: 중복 판정 (양방향) ───────────────────────────────

    @Test
    fun `create - 내가 건 PENDING 이 있으면 중복이다`() {
        seedUsersAndFriendship()
        service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY)

        val e = assertThrows<BusinessException> {
            service().createSigned(me, friend, "독서", "밥 사기", DeadlineType.TODAY)
        }
        assertEquals(ChallengeCommandService.MSG_DUPLICATE_OWN, e.message)
    }

    @Test
    fun `create - 상대가 나에게 건 PENDING 도 중복이고 문구가 다르다`() {
        seedUsersAndFriendship()
        // 상대가 나에게 먼저 걸었다.
        service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY)

        val e = assertThrows<BusinessException> {
            service().createSigned(me, friend, "독서", "밥 사기", DeadlineType.TODAY)
        }
        // "진행 중" 이라고 하면 홈에서 아무것도 안 보여 혼란스럽다 → 받은 도전장으로 안내.
        assertEquals(ChallengeCommandService.MSG_DUPLICATE_REVERSE, e.message)
    }

    @Test
    fun `create - IN_PROGRESS 챌린지가 있으면 방향과 무관하게 중복이다`() {
        seedUsersAndFriendship()
        challenges.put(
            challenge(
                id = 50L,
                challengerId = friend,
                opponentId = me,
                status = ChallengeStatus.IN_PROGRESS,
                challengeDate = LocalDate.of(2026, 7, 28),
            ),
        )

        val e = assertThrows<BusinessException> {
            service().createSigned(me, friend, "독서", "밥 사기", DeadlineType.TODAY)
        }
        // IN_PROGRESS 는 역방향이어도 PENDING 이 아니므로 "받은 도전장" 안내가 아니다.
        assertEquals(ChallengeCommandService.MSG_DUPLICATE_OWN, e.message)
    }

    @Test
    fun `create - REJECTED 는 중복이 아니라 재도전이 가능하다`() {
        seedUsersAndFriendship()
        challenges.put(
            challenge(
                id = 51L,
                challengerId = me,
                opponentId = friend,
                status = ChallengeStatus.REJECTED,
                challengeDate = LocalDate.of(2026, 7, 28),
            ),
        )

        val created = service().createSigned(me, friend, "독서", "밥 사기", DeadlineType.TODAY)

        assertEquals(ChallengeStatus.PENDING, created.status)
    }

    @Test
    fun `create - 날짜가 다르면 중복이 아니다 - 같은 친구에게 오늘과 내일 둘 다 가능`() {
        seedUsersAndFriendship()
        service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY)

        val tomorrow = service().createSigned(me, friend, "독서", "밥 사기", DeadlineType.TOMORROW)

        assertEquals(LocalDate.of(2026, 7, 29), tomorrow.challengeDate)
        assertEquals(2, challenges.all().size)
    }

    @Test
    fun `create - 다른 친구와는 같은 날 각각 걸 수 있다`() {
        seedUsersAndFriendship()
        val friend2 = 4L
        users.put(user(friend2, "영희"))
        friendships.put(
            Friendship(
                id = 102L, requesterId = friend2, receiverId = me,
                status = FriendshipStatus.ACCEPTED,
                createdAt = LocalDateTime.of(2026, 7, 1, 0, 0),
                acceptedAt = LocalDateTime.of(2026, 7, 1, 0, 0),
            ),
        )
        service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY)

        val second = service().createSigned(me, friend2, "독서", "밥 사기", DeadlineType.TODAY)

        assertEquals(ChallengeStatus.PENDING, second.status)
        assertEquals(2, challenges.all().size)
    }

    // ─── accept ──────────────────────────────────────────────────

    @Test
    fun `accept - IN_PROGRESS 로 전이되고 opponentMission 이 채워진다`() {
        seedUsersAndFriendship()
        val created = service().createSigned(friend, me, "운동 1시간", "커피 사기", DeadlineType.TODAY)
        val id = created.id!!

        val accepted = service().acceptSigned(me, id, "  책 30페이지  ")

        assertEquals(ChallengeStatus.IN_PROGRESS, accepted.status)
        assertEquals("책 30페이지", accepted.opponentMission)
        assertEquals("운동 1시간", accepted.challengerMission)
    }

    @Test
    fun `accept - verifications PENDING row 가 양측 2건 생성된다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!

        service().acceptSigned(me, id, "책 30페이지")

        val rows = verifications.all().filter { it.challengeId == id }
        assertEquals(2, rows.size)
        assertEquals(setOf(friend, me), rows.map { it.userId }.toSet())
        assertTrue(rows.all { it.status == VerificationStatus.PENDING })
        assertTrue(rows.all { it.photoUrl == null && it.verifiedAt == null })
    }

    @Test
    fun `accept - 당사자가 아니면 거부된다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!

        val e = assertThrows<BusinessException> { service().acceptSigned(stranger, id, "책") }

        assertEquals(ResponseCode.SNACKBAR_ERROR, e.code)
        assertEquals(ChallengeCommandService.MSG_NOT_MY_RECEIVED, e.message)
    }

    @Test
    fun `accept - 챌린저 본인은 자기 도전장을 수락할 수 없다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!

        val e = assertThrows<BusinessException> { service().acceptSigned(friend, id, "책") }

        assertEquals(ChallengeCommandService.MSG_NOT_MY_RECEIVED, e.message)
    }

    @Test
    fun `accept - 이미 수락된 챌린지는 705 로 거부된다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!
        service().acceptSigned(me, id, "책 30페이지")

        val e = assertThrows<BusinessException> { service().acceptSigned(me, id, "다시") }

        assertEquals(ResponseCode.ONE_BUTTON_DIALOG_ERROR, e.code)
        assertEquals(ChallengeCommandService.MSG_ALREADY_HANDLED, e.message)
    }

    @Test
    fun `accept - 존재하지 않는 id 는 705 로 거부된다`() {
        seedUsersAndFriendship()

        val e = assertThrows<BusinessException> { service().acceptSigned(me, 9999L, "책") }

        assertEquals(ResponseCode.ONE_BUTTON_DIALOG_ERROR, e.code)
        assertEquals(ChallengeCommandService.MSG_NOT_FOUND, e.message)
    }

    @Test
    fun `accept - 마감이 지나면 705 로 거부된다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!

        // 마감(KST 07-29 00:00) 1초 후로 시계를 옮긴다. 07-28T15:00:01Z == KST 07-29 00:00:01
        val later = Clock.fixed(Instant.parse("2026-07-28T15:00:01Z"), KstTime.ZONE)
        val e = assertThrows<BusinessException> { service(later).acceptSigned(me, id, "책") }

        assertEquals(ResponseCode.ONE_BUTTON_DIALOG_ERROR, e.code)
        assertEquals(ChallengeCommandService.MSG_EXPIRED, e.message)
    }

    @Test
    fun `accept - 마감 정각은 아직 수락할 수 있다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!

        val atDeadline = Clock.fixed(Instant.parse("2026-07-28T15:00:00Z"), KstTime.ZONE) // KST 07-29 00:00 정각
        val accepted = service(atDeadline).acceptSigned(me, id, "책")

        assertEquals(ChallengeStatus.IN_PROGRESS, accepted.status)
    }

    @Test
    fun `accept - 권한 검사가 상태 검사보다 먼저다 - 남의 챌린지 상태를 흘리지 않는다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!
        service().acceptSigned(me, id, "책 30페이지") // 이미 IN_PROGRESS

        val e = assertThrows<BusinessException> { service().acceptSigned(stranger, id, "책") }

        // "이미 처리된 챌린지예요" 가 나가면 제3자가 남의 챌린지 진행 여부를 알아낼 수 있다.
        assertEquals(ChallengeCommandService.MSG_NOT_MY_RECEIVED, e.message)
    }

    @Test
    fun `accept - 미션이 공백뿐이면 거부된다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!

        val e = assertThrows<BusinessException> { service().acceptSigned(me, id, "  ") }

        assertEquals(ChallengeCommandService.MSG_MISSION_LENGTH, e.message)
    }

    // ─── reject ──────────────────────────────────────────────────

    @Test
    fun `reject - REJECTED 로 전이되고 row 는 남는다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!

        val rejected = service().reject(me, id)

        assertEquals(ChallengeStatus.REJECTED, rejected.status)
        assertEquals(1, challenges.all().size)
    }

    @Test
    fun `reject - 거절 후에는 같은 날 다시 도전할 수 있다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!
        service().reject(me, id)

        val retry = service().createSigned(me, friend, "독서", "밥 사기", DeadlineType.TODAY)

        assertEquals(ChallengeStatus.PENDING, retry.status)
    }

    @Test
    fun `reject - 당사자가 아니면 거부된다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!

        val e = assertThrows<BusinessException> { service().reject(stranger, id) }

        assertEquals(ChallengeCommandService.MSG_NOT_MY_RECEIVED, e.message)
    }

    @Test
    fun `reject - 이미 처리된 챌린지는 705 로 거부된다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!
        service().reject(me, id)

        val e = assertThrows<BusinessException> { service().reject(me, id) }

        assertEquals(ResponseCode.ONE_BUTTON_DIALOG_ERROR, e.code)
        assertEquals(ChallengeCommandService.MSG_ALREADY_HANDLED, e.message)
    }

    // ─── cancel ──────────────────────────────────────────────────

    @Test
    fun `cancel - 챌린저가 취소하면 물리 삭제된다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY).id!!

        service().cancel(me, id)

        assertTrue(challenges.all().isEmpty())
    }

    @Test
    fun `cancel - 취소 후 같은 날 다시 걸 수 있다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY).id!!
        service().cancel(me, id)

        val again = service().createSigned(me, friend, "독서", "밥 사기", DeadlineType.TODAY)

        assertEquals(ChallengeStatus.PENDING, again.status)
    }

    @Test
    fun `cancel - 챌린저가 아니면 거부된다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY).id!!

        // 받는 쪽(friend)이 취소를 시도.
        val e = assertThrows<BusinessException> { service().cancel(friend, id) }

        assertEquals(ResponseCode.SNACKBAR_ERROR, e.code)
        assertEquals(ChallengeCommandService.MSG_NOT_MY_SENT, e.message)
        assertEquals(1, challenges.all().size)
    }

    @Test
    fun `cancel - 이미 수락된 챌린지는 취소할 수 없다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY).id!!
        service().acceptSigned(friend, id, "책 30페이지")

        val e = assertThrows<BusinessException> { service().cancel(me, id) }

        assertEquals(ResponseCode.ONE_BUTTON_DIALOG_ERROR, e.code)
        assertEquals(ChallengeCommandService.MSG_ALREADY_HANDLED, e.message)
    }

    @Test
    fun `cancel - 마감이 지나도 내 도전장은 치울 수 있다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY).id!!

        val later = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), KstTime.ZONE)
        service(later).cancel(me, id)

        assertTrue(challenges.all().isEmpty())
    }

    // ─── listReceived ────────────────────────────────────────────

    @Test
    fun `listReceived - 마감 미경과 PENDING 만 반환한다`() {
        seedUsersAndFriendship()
        service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY)

        assertEquals(1, service().listReceived(me).size)

        // 마감 후에는 응답에서 빠진다 (DB status 는 PENDING 그대로 — lazy expiry).
        val later = Clock.fixed(Instant.parse("2026-07-28T15:00:01Z"), KstTime.ZONE)
        assertEquals(0, service(later).listReceived(me).size)
        assertEquals(ChallengeStatus.PENDING, challenges.all().single().status)
    }

    @Test
    fun `listReceived - 내가 보낸 것은 포함되지 않는다`() {
        seedUsersAndFriendship()
        service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY)

        assertEquals(0, service().listReceived(me).size)
        assertEquals(1, service().listReceived(friend).size)
    }


    // ─── 영혼의 맹세 (soul-oath) ─────────────────────────────────

    @Test
    fun `생성하면 계약서가 함께 만들어지고 챌린저 서명만 채워진다`() {
        seedUsersAndFriendship()

        val created = service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY)

        val contract = contracts.findByChallengeId(created.id!!)!!
        assertEquals(VALID_SIGNATURE, contract.challengerSignatureData)
        assertNotNull(contract.challengerSignedAt)
        assertNull(contract.opponentSignatureData, "상대 서명은 수락 전까지 비어 있어야 한다")
        assertNull(contract.opponentSignedAt)
        assertFalse(contract.isFinalized)
        assertTrue(contract.content.isNotBlank(), "계약서 본문이 렌더돼야 한다")
    }

    @Test
    fun `수락하면 상대 서명이 채워지고 isFinalized 가 true 가 된다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!
        val opponentSig = """{"v":1,"g":1000,"s":[[1,2,3,4],[5,6,7,8]]}"""

        val accepted = service().acceptSigned(me, id, "책 30페이지", opponentSig)

        val contract = contracts.findByChallengeId(id)!!
        assertEquals(opponentSig, contract.opponentSignatureData)
        assertNotNull(contract.opponentSignedAt)
        assertTrue(contract.isFinalized)
        // is_finalized 가 true 가 된 시점에만 IN_PROGRESS (spec 수용 기준)
        assertEquals(ChallengeStatus.IN_PROGRESS, accepted.status)
    }

    // ─── 본문 재렌더 (design-bridge 검토 수정 2) ──────────────────

    @Test
    fun `수락 전 본문에는 상대 미션이 자리표시로 남는다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!

        val content = contracts.findByChallengeId(id)!!.content
        assertTrue(
            content.contains(ContractContent.PENDING_MISSION),
            "수락 전에는 상대 미션을 알 수 없다:\n$content",
        )
    }

    @Test
    fun `완결 시 본문이 실제 상대 미션으로 재렌더된다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!

        service().acceptSigned(me, id, "책 30페이지")

        val content = contracts.findByChallengeId(id)!!.content
        // 🔴 신청 시점에 한 번만 박제하면 완성된 계약서에 한쪽 약속이 영영 비어 있다.
        assertFalse(
            content.contains(ContractContent.PENDING_MISSION),
            "완결된 계약서에 자리표시가 남았다:\n$content",
        )
        assertTrue(content.contains("· 나의 미션: 책 30페이지"), content)
        assertTrue(content.contains("· 민수의 미션: 운동"), content)
    }

    @Test
    fun `완결된 뒤에는 본문이 더 이상 바뀌지 않는다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!
        service().acceptSigned(me, id, "책 30페이지")
        val finalized = contracts.findByChallengeId(id)!!.content

        // 재서명이 거부되므로 재렌더 경로는 계약당 한 번만 실행된다.
        assertThrows<BusinessException> { service().acceptSigned(me, id, "다른 미션") }

        assertEquals(
            finalized,
            contracts.findByChallengeId(id)!!.content,
            "완결 후 본문이 바뀌면 '무를 수 없는 약속' 이 성립하지 않는다",
        )
    }

    @Test
    fun `서명은 저장 왕복에서 문자열이 그대로 보존된다`() {
        seedUsersAndFriendship()
        val sig = """{"v":1,"g":1000,"s":[[0,0,1000,1000],[500,500]]}"""

        val created = service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY, sig)

        assertEquals(sig, contracts.findByChallengeId(created.id!!)!!.challengerSignatureData)
    }

    @Test
    fun `빈 서명은 거부된다`() {
        seedUsersAndFriendship()

        for (empty in listOf("""{"v":1,"g":1000,"s":[]}""", """{"v":1,"g":1000,"s":[[]]}""")) {
            val e = assertThrows<BusinessException> {
                service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY, empty)
            }
            assertEquals(ResponseCode.SNACKBAR_ERROR, e.code)
            assertEquals("서명을 해주세요", e.message)
        }
        assertTrue(challenges.all().isEmpty(), "거부된 요청이 챌린지를 만들면 안 된다")
    }

    @Test
    fun `점 1개짜리 획도 유효한 서명이다`() {
        seedUsersAndFriendship()

        // 최소 점 개수를 서버가 발명하지 않는다 — 짧게 서명하는 사람을 오거부하게 된다.
        val created = service().createSigned(
            me, friend, "운동", "커피", DeadlineType.TODAY, """{"v":1,"g":1000,"s":[[500,500]]}""",
        )

        assertEquals(ChallengeStatus.PENDING, created.status)
    }

    @Test
    fun `취소하면 계약서도 함께 사라진다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY).id!!
        assertNotNull(contracts.findByChallengeId(id))

        service().cancel(me, id)

        // FK 정합 — contract 를 먼저 지우지 않으면 실 DB 에서 FK 위반 → 500 이다.
        assertNull(contracts.findByChallengeId(id), "취소 시 계약서가 남으면 FK 위반이 난다")
        assertTrue(challenges.all().isEmpty())
    }

    @Test
    fun `거절해도 계약서는 보존된다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!

        service().reject(me, id)

        // 거절은 challenge row 를 보존하므로 FK 문제가 없다. "거절당한 맹세"도 이력이다.
        assertNotNull(contracts.findByChallengeId(id), "거절 시 계약서는 남아야 한다")
        assertEquals(ChallengeStatus.REJECTED, challenges.all().single().status)
    }

    // ─── 알림 이벤트 발행 (push-fcm T-B5) ─────────────────────────
    //
    // 🔴 여기서 검증하는 것은 **수신자와 타입**이다. 발송 자체는 커밋 후 리스너의 몫이고,
    //    "누구에게 가는가" 를 여기서 틀리면 상대에게 갈 알림이 본인에게 간다.

    @Test
    fun `신청하면 상대에게 CHALLENGE_REQUEST 이벤트가 발행된다`() {
        seedUsersAndFriendship()

        val created = service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY)

        val event = events.challengeEvents().single()
        assertEquals(NotificationType.CHALLENGE_REQUEST, event.type)
        assertEquals(friend, event.recipientId, "신청 알림은 **상대**가 받는다")
        assertEquals(me, event.actorId)
        assertEquals(created.id, event.challengeId)
    }

    @Test
    fun `수락하면 신청자에게 CHALLENGE_ACCEPTED 이벤트가 발행된다`() {
        seedUsersAndFriendship()
        // friend 가 me 에게 신청 → me 가 수락한다.
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!
        events.clear()

        service().acceptSigned(me, id, "책 30페이지")

        val event = events.challengeEvents().single()
        assertEquals(NotificationType.CHALLENGE_ACCEPTED, event.type)
        // 🔴 수락한 본인(me)이 아니라 **신청자(friend)** 가 받는다. 뒤바뀌면 자기 알림을 자기가 받는다.
        assertEquals(friend, event.recipientId)
        assertEquals(me, event.actorId)
        assertEquals(id, event.challengeId)
    }

    @Test
    fun `거절하면 신청자에게 CHALLENGE_REJECTED 이벤트가 발행된다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!
        events.clear()

        service().reject(me, id)

        val event = events.challengeEvents().single()
        assertEquals(NotificationType.CHALLENGE_REJECTED, event.type)
        assertEquals(friend, event.recipientId)
        assertEquals(me, event.actorId)
    }

    /**
     * 요청이 거부되면 이벤트도 없어야 한다.
     *
     * 실패 경로에서 발행하면 **일어나지 않은 일에 대한 알림**이 나간다. 트랜잭션 롤백은
     * 이미 발행된 이벤트를 되돌려 주지만(커밋이 없으면 `AFTER_COMMIT` 리스너가 안 돈다),
     * 애초에 발행 지점이 성공 경로 뒤에 있어야 그 보장에 기대지 않는다.
     */
    @Test
    fun `신청이 거부되면 이벤트를 발행하지 않는다`() {
        seedUsersAndFriendship()

        assertThrows<BusinessException> {
            // 친구가 아닌 상대 → SnackbarException
            service().createSigned(me, stranger, "운동", "커피", DeadlineType.TODAY)
        }

        assertTrue(events.challengeEvents().isEmpty(), "실패한 신청에 알림이 발행됐다: ${events.challengeEvents()}")
    }

    @Test
    fun `취소는 알림을 발행하지 않는다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(me, friend, "운동", "커피", DeadlineType.TODAY).id!!
        events.clear()

        service().cancel(me, id)

        // spec 비범위 표: "상대가 아직 도전장을 보지 못한 경우가 대부분이라 알림 가치가 낮다"
        assertTrue(events.challengeEvents().isEmpty(), "취소에는 알림이 없어야 한다")
    }

    @Test
    fun `이미 맹세를 마친 계약에는 재서명할 수 없다`() {
        seedUsersAndFriendship()
        val id = service().createSigned(friend, me, "운동", "커피", DeadlineType.TODAY).id!!
        service().acceptSigned(me, id, "책 30페이지")

        val e = assertThrows<BusinessException> { service().acceptSigned(me, id, "다시") }

        // 상태 가드(이미 처리됨)가 먼저 잡는다. contract 레벨 가드는 2차 방어선이다.
        assertEquals(ResponseCode.ONE_BUTTON_DIALOG_ERROR, e.code)
    }

    // ─── 서명 헬퍼 ────────────────────────────────────────────────
    //
    // soul-oath 이후 create/accept 가 서명을 요구한다. **기존 단언의 의미는 그대로**이고
    // 인자만 늘었으므로, 매 호출부에 상수를 붙이는 대신 헬퍼로 감싼다.
    // 서명 자체의 검증은 아래 "영혼의 맹세" 섹션과 SignatureValidatorTest 가 따로 다룬다.

    private fun ChallengeCommandService.createSigned(
        me: Long, opponentId: Long, myMission: String, betContent: String, deadlineType: DeadlineType,
        signature: String = VALID_SIGNATURE,
    ) = create(me, opponentId, myMission, betContent, deadlineType, signature)

    private fun ChallengeCommandService.acceptSigned(
        me: Long, challengeId: Long, myMission: String, signature: String = VALID_SIGNATURE,
    ) = accept(me, challengeId, myMission, signature)

    // ─── fixtures ────────────────────────────────────────────────

    private fun user(id: Long, nickname: String, status: UserStatus = UserStatus.ACTIVE) = User(
        id = id,
        kakaoId = 10_000L + id,
        nickname = nickname,
        profileImageUrl = null,
        phoneNumber = null,
        phoneVerified = false,
        fcmToken = null,
        status = status,
        createdAt = LocalDateTime.of(2026, 1, 1, 0, 0),
        updatedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
    )

    private fun challenge(
        id: Long,
        challengerId: Long,
        opponentId: Long,
        status: ChallengeStatus,
        challengeDate: LocalDate,
    ) = Challenge(
        id = id,
        challengerId = challengerId,
        opponentId = opponentId,
        challengerMission = "기존 미션",
        opponentMission = if (status == ChallengeStatus.IN_PROGRESS) "상대 미션" else null,
        betContent = "기존 내기",
        challengeDate = challengeDate,
        deadline = KstDeadlineCalculator.endOfDay(challengeDate),
        status = status,
        result = null,
        createdAt = LocalDateTime.of(2026, 7, 28, 0, 0),
        completedAt = null,
    )
}

// ─── in-memory fakes ─────────────────────────────────────────────

private class FakeChallengeRepository : ChallengeRepository {
    private val store = LinkedHashMap<Long, Challenge>()
    private var nextId = 1L

    fun put(c: Challenge) {
        store[c.id!!] = c
        nextId = maxOf(nextId, c.id!! + 1)
    }

    fun all(): List<Challenge> = store.values.toList()
    fun clear() = store.clear()

    override fun findActiveByUser(userId: Long, statuses: Collection<ChallengeStatus>): List<Challenge> =
        store.values
            .filter { (it.challengerId == userId || it.opponentId == userId) && it.status in statuses }
            .sortedBy { it.deadline }

    override fun findById(id: Long): Challenge? = store[id]

    override fun findReceivedPending(opponentId: Long, now: LocalDateTime): List<ReceivedChallenge> =
        store.values
            .filter {
                it.opponentId == opponentId &&
                    it.status == ChallengeStatus.PENDING &&
                    it.deadline > now
            }
            .sortedByDescending { it.createdAt }
            .map {
                ReceivedChallenge(
                    challengeId = it.id!!,
                    challengerId = it.challengerId,
                    challengerNickname = "챌린저${it.challengerId}",
                    challengerProfileImageUrl = null,
                    challengerMission = it.challengerMission,
                    betContent = it.betContent,
                    challengeDate = it.challengeDate,
                    deadline = it.deadline,
                    createdAt = it.createdAt,
                )
            }

    override fun findConflicting(
        userA: Long,
        userB: Long,
        challengeDate: LocalDate,
        statuses: Collection<ChallengeStatus>,
    ): List<Challenge> = store.values.filter {
        val samePair = (it.challengerId == userA && it.opponentId == userB) ||
            (it.challengerId == userB && it.opponentId == userA)
        samePair && it.challengeDate == challengeDate && it.status in statuses
    }

    override fun save(challenge: Challenge): Challenge {
        val id = challenge.id ?: nextId++
        val saved = challenge.copy(id = id)
        store[id] = saved
        return saved
    }

    override fun saveAndFlush(challenge: Challenge): Challenge = save(challenge)

    override fun deleteById(id: Long) {
        store.remove(id)
    }
}

private class FakeFriendshipRepository : FriendshipRepository {
    private val store = mutableListOf<Friendship>()

    fun put(f: Friendship) {
        store.removeAll { it.id == f.id }
        store.add(f)
    }

    override fun findBetween(userA: Long, userB: Long): List<Friendship> = store.filter {
        (it.requesterId == userA && it.receiverId == userB) ||
            (it.requesterId == userB && it.receiverId == userA)
    }

    override fun findById(id: Long): Friendship? = store.firstOrNull { it.id == id }

    override fun searchByNickname(me: Long, escapedPattern: String): List<UserSearchResult> = emptyList()
    override fun findFriendsOf(me: Long): List<Friend> = emptyList()
    override fun findReceivedRequestsOf(me: Long): List<FriendRequest> = emptyList()
    override fun save(friendship: Friendship): Friendship = friendship.also { put(it) }
    override fun saveAndFlush(friendship: Friendship): Friendship = save(friendship)
    override fun deleteById(id: Long) {
        store.removeAll { it.id == id }
    }
}

private class FakeUserRepository : UserRepository {
    private val store = LinkedHashMap<Long, User>()

    fun put(u: User) {
        store[u.id!!] = u
    }

    override fun findById(id: Long): User? = store[id]
    override fun findByKakaoId(kakaoId: Long): User? = store.values.firstOrNull { it.kakaoId == kakaoId }
    override fun findAllByIds(ids: Collection<Long>): Map<Long, User> =
        ids.mapNotNull { store[it] }.associateBy { it.id!! }

    override fun save(user: User): User = user.also { put(it) }
    override fun updateRefreshTokenHash(userId: Long, hash: String?, issuedAt: LocalDateTime?) = Unit

    // FCM 토큰은 이 서비스의 관심사가 아니다. 동작 검증은 UserServiceFcmTokenTest / AuthServiceLogoutTest 가 한다.
    override fun clearFcmTokenOwnedByOthers(fcmToken: String, exceptUserId: Long): Int = 0
    override fun updateFcmToken(userId: Long, fcmToken: String?): Int = 1
}

/**
 * 발행된 이벤트를 그대로 모아 두는 [ApplicationEventPublisher] (push-fcm T-B5).
 *
 * ⚠️ `AFTER_COMMIT` 의미를 흉내내지 **않는다** — 발행 즉시 기록한다. 트랜잭션 경계는 이 단위
 * 테스트에 존재하지 않으므로, 여기서 알 수 있는 것은 *무엇을 발행했는가* 뿐이다.
 */
private class RecordingEventPublisher : ApplicationEventPublisher {

    private val published = mutableListOf<Any>()

    override fun publishEvent(event: Any) {
        published += event
    }

    fun challengeEvents(): List<ChallengeNotificationEvent> =
        published.filterIsInstance<ChallengeNotificationEvent>()

    fun clear() = published.clear()
}

private class FakeVerificationRepository : VerificationRepository {
    private val store = mutableListOf<Verification>()
    private var nextId = 1L

    fun all(): List<Verification> = store.toList()

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
}


private class FakeContractRepository : ContractRepository {
    private val store = LinkedHashMap<Long, Contract>()
    private var nextId = 1L

    fun all(): List<Contract> = store.values.toList()

    override fun findByChallengeId(challengeId: Long): Contract? = store[challengeId]

    override fun save(contract: Contract): Contract {
        val saved = contract.copy(id = contract.id ?: nextId++)
        store[saved.challengeId] = saved
        return saved
    }

    override fun deleteByChallengeId(challengeId: Long) {
        store.remove(challengeId)
    }
}
