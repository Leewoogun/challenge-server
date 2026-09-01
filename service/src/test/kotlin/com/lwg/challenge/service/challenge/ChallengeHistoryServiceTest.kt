package com.lwg.challenge.service.challenge

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeResult
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.Outcome
import com.lwg.challenge.domain.challenge.ReceivedChallenge
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.user.UserStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * [ChallengeHistoryService] 단위 테스트 (mypage T-B1, 2026-08-28 월 단위 개정).
 *
 * ## 무엇을 덮는가
 *
 * **1차부터 있던 축 (개정에서 안 바뀐 것)**
 * 1. **정렬**: `challengeDate DESC`, 같은 날은 `id DESC`.
 * 2. **`COMPLETED` 만**: `EXPIRED`·`IN_PROGRESS` 는 목록에 없다.
 * 3. **역할 → 내 시점 환산**: 같은 row 가 조회자에 따라 `WIN` 이 되기도 `LOSE` 가 되기도 한다.
 *    뒤집기 자체는 `ChallengeVerdict` 의 것이고 여기서 보는 것은 **인자를 옳게 넘겼는가**다
 *    (`isChallenger` 를 반대로 넘기는 실수는 컴파일도 통과하고 타입도 맞는다).
 * 4. **탈퇴자 닉네임 / 빈 목록 / 쿼리 횟수**.
 *
 * **개정으로 생긴 축**
 * 5. **월 범위**: 다른 달은 안 담긴다. 경계는 반개구간(1일 포함, 익월 1일 제외)이고 2월도 옳다.
 * 6. **기본값**: `requestedMonth = null` 이면 **서버 시계의 이번 달**이다 — 앱이 기기 시계로
 *    이번 달을 계산하지 않아도 되게 하는 것이 이 기본값의 존재 이유다.
 * 7. **과거 하한**: `firstArchivedMonth` — 가장 오래된 `COMPLETED` 기록의 월. 🔴 **절대값이고
 *    요청한 달과 무관하게 매 응답 같은 값**이며, **기록 0건이면 `null`** 이다.
 *    (불리언 `hasPrevious`/`hasNext` 는 연타에서 경계를 한 탭 늦게 알려줘 기각됐다 —
 *    `ChallengeHistoryPage.firstArchivedMonth` KDoc.)
 * 8. **미래 달 거부**: `SnackbarException`(code 700). clamp 하지 않는다.
 *
 * ⚠️ **저장소를 흉내낸 것은 필터·정렬·MIN 까지다.** 실제 JPQL 이 반개구간으로 자르는지는
 * 여기서 알 수 없다 — fake 가 같은 규칙을 다시 구현할 뿐이다. 그 축은 throwaway DB 실구동으로
 * 따로 확인한다 (backend-report).
 *
 * fake 를 **중첩 클래스**로 둔 이유: 같은 패키지에 `FakeChallenges`/`FakeChallengeRepository`/
 * `FakeUserRepository` 가 이미 있어 top-level 로 두면 JVM 클래스명이 충돌한다
 * (`ActiveChallengeServiceTest` 와 같은 사유).
 */
class ChallengeHistoryServiceTest {

    private val me = 1L
    private val other = 2L

    /** 서버 "지금" = 2026-08-28 10:00 KST. 이번 달 = 2026-08 이 이 파일 전체의 전제다. */
    private val now = LocalDateTime.of(2026, 8, 28, 10, 0)
    private val thisMonth = YearMonth.of(2026, 8)

    private val challenges = FakeHistoryStore()
    private val users = FakeHistoryUsers()

    private val service = ChallengeHistoryService(
        userRepository = users,
        challengeRepository = challenges,
        clock = Clock.fixed(now.atZone(KST).toInstant(), KST),
    )

    init {
        users.put(user(me, "나"))
        users.put(user(other, "민수"))
    }

    // ─── 1. 정렬 — challengeDate DESC, 같은 날은 id DESC ──────────

    @Test
    fun `최근 날짜가 먼저 나온다`() {
        challenges.put(completed(id = 10L, challengerId = me, date = LocalDate.of(2026, 7, 20)))
        challenges.put(completed(id = 11L, challengerId = me, date = LocalDate.of(2026, 7, 3)))
        challenges.put(completed(id = 12L, challengerId = me, date = LocalDate.of(2026, 7, 31)))

        val page = service.getHistories(me, YearMonth.of(2026, 7))

        assertEquals(listOf(12L, 10L, 11L), page.histories.map { it.challengeId })
    }

    /**
     * 🔴 `challengeDate` 는 날짜라 **같은 날 여러 건이 가능하다.** 2차 키가 없으면 동률의 순서가
     * DB 기분에 달리고, 같은 목록을 두 번 불러올 때 카드 순서가 바뀐다.
     */
    @Test
    fun `같은 날 두 건은 id 내림차순이다`() {
        val sameDay = LocalDate.of(2026, 8, 10)
        challenges.put(completed(id = 20L, challengerId = me, date = sameDay))
        challenges.put(completed(id = 21L, challengerId = me, date = sameDay))

        val page = service.getHistories(me, thisMonth)

        assertEquals(listOf(21L, 20L), page.histories.map { it.challengeId })
    }

    /**
     * 🔴 **범위·정렬 축이 `completedAt` 이 아니라 `challengeDate` 라는 것을 고정한다.**
     *
     * 판정이 자정 직후 배치라 7월 31일 챌린지의 `completedAt` 은 8월 1일 00:05 다. 여기서는
     * 그 상황을 그대로 만들었다 — **7월을 조회하는데 7월 31일 건의 판정 시각은 8월**이다.
     * `completed_at` 으로 범위를 자르면 이 건이 7월 응답에서 빠지고 8월 응답에 나타난다.
     */
    @Test
    fun `판정 시각이 아니라 챌린지 날짜로 월을 자른다`() {
        challenges.put(
            completed(
                id = 30L,
                challengerId = me,
                date = LocalDate.of(2026, 7, 31),
                // 자정 직후 배치 — 날짜는 7월인데 판정은 8월이다.
                completedAt = LocalDateTime.of(2026, 8, 1, 0, 5),
            ),
        )

        assertEquals(
            listOf(30L),
            service.getHistories(me, YearMonth.of(2026, 7)).histories.map { it.challengeId },
            "completed_at 으로 자르면 7월 마지막 날 챌린지가 7월 응답에서 사라진다",
        )
        assertTrue(
            service.getHistories(me, thisMonth).histories.isEmpty(),
            "completed_at 으로 자르면 7월 31일 챌린지가 8월 응답에 나타난다",
        )
    }

    // ─── 2. 월 범위 ──────────────────────────────────────────────

    /** 🔴 개정의 핵심 — 요청한 달의 것만 담긴다. 1차는 전부 담았다. */
    @Test
    fun `요청한 달의 기록만 담긴다`() {
        challenges.put(completed(id = 40L, challengerId = me, date = LocalDate.of(2026, 6, 15)))
        challenges.put(completed(id = 41L, challengerId = me, date = LocalDate.of(2026, 7, 15)))
        challenges.put(completed(id = 42L, challengerId = me, date = LocalDate.of(2026, 8, 15)))

        assertEquals(listOf(41L), service.getHistories(me, YearMonth.of(2026, 7)).histories.map { it.challengeId })
    }

    /**
     * 🔴 반개구간 경계 — **1일은 포함, 익월 1일은 제외.** 전월 말일도 제외.
     *
     * 이 단언이 없으면 `>=`/`>` 를 한 칸 틀려도 대부분의 테스트가 통과한다 (경계 밖의 데이터로만
     * 채우면 아무 차이가 없다).
     */
    @Test
    fun `월 경계는 1일 포함 익월 1일 제외다`() {
        challenges.put(completed(id = 50L, challengerId = me, date = LocalDate.of(2026, 6, 30)))
        challenges.put(completed(id = 51L, challengerId = me, date = LocalDate.of(2026, 7, 1)))
        challenges.put(completed(id = 52L, challengerId = me, date = LocalDate.of(2026, 7, 31)))
        challenges.put(completed(id = 53L, challengerId = me, date = LocalDate.of(2026, 8, 1)))

        assertEquals(
            listOf(52L, 51L),
            service.getHistories(me, YearMonth.of(2026, 7)).histories.map { it.challengeId },
        )
    }

    /**
     * 🔴 2월. 반개구간이라 **말일 계산 자체가 없다** — `atEndOfMonth()` 를 썼다면 28/29 분기가
     * 생겼을 자리다. 2026 은 평년이라 2월이 28일까지다.
     */
    @Test
    fun `2월도 말일 계산 없이 옳다`() {
        challenges.put(completed(id = 60L, challengerId = me, date = LocalDate.of(2026, 2, 28)))
        challenges.put(completed(id = 61L, challengerId = me, date = LocalDate.of(2026, 3, 1)))

        assertEquals(
            listOf(60L),
            service.getHistories(me, YearMonth.of(2026, 2)).histories.map { it.challengeId },
        )
    }

    // ─── 3. 기본값 = 이번 달 ─────────────────────────────────────

    /**
     * 🔴 **이 기본값이 "첫 진입은 항상 이번 달" 의 구현이다.** 앱이 기기 시계로 이번 달을
     * 계산해 보내지 않아도 되게 한다 — 상한(이번 달)과 하한이 **같은 시계**에서 나오게 하는 것이
     * 목적이다.
     */
    @Test
    fun `월을 지정하지 않으면 서버 시계의 이번 달이다`() {
        challenges.put(completed(id = 70L, challengerId = me, date = LocalDate.of(2026, 8, 5)))
        challenges.put(completed(id = 71L, challengerId = me, date = LocalDate.of(2026, 7, 5)))

        val page = service.getHistories(me, requestedMonth = null)

        assertEquals(thisMonth, page.month)
        assertEquals(listOf(70L), page.histories.map { it.challengeId })
    }

    /** 응답의 `month` 는 요청 값의 에코다 — 앱이 늦게 온 옛 응답을 버리는 근거로 쓴다. */
    @Test
    fun `요청한 월이 그대로 에코된다`() {
        assertEquals(YearMonth.of(2026, 3), service.getHistories(me, YearMonth.of(2026, 3)).month)
    }

    // ─── 4. firstArchivedMonth — 빈 상태 2종 판별 (경계 아님) ────

    /**
     * 🔴 하한은 **절대값**이고 **요청한 달과 무관하게 매 응답 같은 값**이다. 이게 이 필드의
     * 존재 이유 전부다 — 앱이 어느 달에 서 있든 `<` 활성 여부를 **왕복 없이** 계산할 수 있다.
     *
     * 불리언(`hasPrevious`)이 기각된 이유이기도 하다: 불리언은 **그 달의 응답이 와야** 그 달의
     * 경계를 알려주므로, 연타 중에는 한 탭 늦게 도착해 **사용자가 하한을 넘어간 뒤에** 하한을
     * 통보받는다. 이 단언이 깨지면 그 형태로 되돌아간 것이다.
     */
    @Test
    fun `firstArchivedMonth 는 어느 달을 조회하든 같은 값이다`() {
        challenges.put(completed(id = 90L, challengerId = me, date = LocalDate.of(2026, 3, 20)))
        challenges.put(completed(id = 91L, challengerId = me, date = LocalDate.of(2026, 8, 2)))

        val expected = YearMonth.of(2026, 3)
        assertEquals(expected, service.getHistories(me, thisMonth).firstArchivedMonth)
        assertEquals(expected, service.getHistories(me, YearMonth.of(2026, 3)).firstArchivedMonth)
        assertEquals(expected, service.getHistories(me, YearMonth.of(2026, 5)).firstArchivedMonth, "카드가 0건인 달에서도 같다")
        assertEquals(expected, service.getHistories(me, YearMonth.of(2025, 1)).firstArchivedMonth, "하한 아래에서도 같다")
    }

    /** 날짜가 아니라 **월**로 접어서 준다 — 20일이 아니라 3월이다. */
    @Test
    fun `firstArchivedMonth 는 날짜가 아니라 월이다`() {
        challenges.put(completed(id = 92L, challengerId = me, date = LocalDate.of(2026, 3, 20)))

        assertEquals(YearMonth.of(2026, 3), service.getHistories(me, thisMonth).firstArchivedMonth)
    }

    /**
     * 🔴 **기록이 하나도 없으면 `null` 이다.**
     *
     * 이 null 이 앱에서 *"보관함이 통째로 비었다"* 의 신호가 된다 — 빈 달 화면이 아니라 기록
     * 전무 화면을 그리고, 후자만 *"챌린지 만들기"* CTA 를 띄운다(기록을 찾는 중인 사용자를
     * 보관함 밖으로 내보내면 안 된다).
     */
    @Test
    fun `종료 기록이 하나도 없으면 firstArchivedMonth 가 null 이다`() {
        challenges.put(notCompleted(id = 100L, challengerId = me, status = ChallengeStatus.IN_PROGRESS))

        val page = service.getHistories(me, requestedMonth = null)

        assertTrue(page.histories.isEmpty())
        assertNull(page.firstArchivedMonth, "기록 0건은 하한이 없다 — 앱이 이걸로 기록 전무 화면을 그린다")
    }

    /**
     * 🔴 하한은 **`COMPLETED` 만** 센다. `EXPIRED`/`IN_PROGRESS` 가 더 과거에 있어도 하한을
     * 끌어내리지 않는다 — 그 달로 가 봐야 목록 조건도 `COMPLETED` 라 빈 화면이다.
     */
    @Test
    fun `하한은 COMPLETED 만 센다`() {
        challenges.put(notCompleted(id = 110L, challengerId = me, status = ChallengeStatus.EXPIRED, date = LocalDate.of(2026, 1, 5)))
        challenges.put(completed(id = 111L, challengerId = me, date = LocalDate.of(2026, 6, 5)))

        assertEquals(
            YearMonth.of(2026, 6),
            service.getHistories(me, YearMonth.of(2026, 6)).firstArchivedMonth,
            "EXPIRED 가 1월에 있어도 하한은 6월이다",
        )
    }

    /**
     * 🔴 **하한 아래는 에러가 아니다** — 그냥 빈 달이다. 미래와 비대칭인 이유: 미래는 데이터가
     * **존재할 수 없는** 구간이고, 과거는 **존재할 수 있었지만 없는** 구간이다.
     *
     * 절대값 덕에 앱은 애초에 여기까지 오지 않지만, 계약 위반에 대한 안전망으로 유지한다.
     */
    @Test
    fun `하한보다 이전 달을 요청해도 에러가 아니라 빈 달이다`() {
        challenges.put(completed(id = 120L, challengerId = me, date = LocalDate.of(2026, 6, 5)))

        val page = service.getHistories(me, YearMonth.of(2025, 1))

        assertTrue(page.histories.isEmpty())
        assertEquals(YearMonth.of(2026, 6), page.firstArchivedMonth, "하한 자체는 그대로 실린다")
    }

    // ─── 5. 미래 달 거부 ─────────────────────────────────────────

    /**
     * 🔴 조용히 이번 달로 clamp 하지 않는다. clamp 하면 응답의 `month` 에코가 요청과 달라져
     * **앱 화면의 월 표시와 카드 내용이 어긋난다.**
     *
     * 정상 경로에서는 발생하지 않는다 — 앱은 직전 응답의 `hasNext = false` 를 보고 `>` 를
     * 비활성화한다. 그래서 이건 계약 위반에 대한 안전망이다.
     */
    @Test
    fun `미래 달 요청은 700 이다`() {
        val e = assertThrows<SnackbarException> { service.getHistories(me, YearMonth.of(2026, 9)) }

        assertEquals(ResponseCode.SNACKBAR_ERROR, e.code)
    }

    @Test
    fun `아주 먼 미래도 마찬가지다`() {
        assertThrows<SnackbarException> { service.getHistories(me, YearMonth.of(2030, 1)) }
    }

    /** 이번 달 자체는 미래가 아니다 — 경계에서 한 칸 틀리면 첫 진입이 통째로 막힌다. */
    @Test
    fun `이번 달은 거부되지 않는다`() {
        assertEquals(thisMonth, service.getHistories(me, thisMonth).month)
    }

    // ─── 6. COMPLETED 만 ─────────────────────────────────────────

    /**
     * 🔴 `EXPIRED` 는 **성립조차 못 한 신청**이라 계약서·서명·사진·결과가 전부 없다 —
     * 보관함에 올리면 증거가 0인 카드가 된다.
     */
    @Test
    fun `COMPLETED 가 아닌 것은 담기지 않는다`() {
        challenges.put(completed(id = 130L, challengerId = me, date = LocalDate.of(2026, 8, 5)))
        challenges.put(notCompleted(id = 131L, challengerId = me, status = ChallengeStatus.EXPIRED))
        challenges.put(notCompleted(id = 132L, challengerId = me, status = ChallengeStatus.IN_PROGRESS))
        challenges.put(notCompleted(id = 133L, challengerId = me, status = ChallengeStatus.REJECTED))

        assertEquals(listOf(130L), service.getHistories(me, thisMonth).histories.map { it.challengeId })
    }

    // ─── 7. myResult — 역할 기준 → 내 시점 ────────────────────────

    @Test
    fun `내가 challenger 면 CHALLENGER_WIN 이 WIN 이다`() {
        challenges.put(
            completed(id = 140L, challengerId = me, date = LocalDate.of(2026, 8, 5), result = ChallengeResult.CHALLENGER_WIN),
        )

        assertEquals(Outcome.WIN, service.getHistories(me, thisMonth).histories.single().myResult)
    }

    @Test
    fun `내가 opponent 면 같은 CHALLENGER_WIN 이 LOSE 다`() {
        // 🔴 위 케이스와 **row 가 동일**하다. 조회자만 다르다 — 같은 데이터가 시점에 따라
        //    반대로 읽힌다는 것이 이 필드의 존재 이유다.
        challenges.put(
            completed(id = 140L, challengerId = other, date = LocalDate.of(2026, 8, 5), result = ChallengeResult.CHALLENGER_WIN),
        )

        assertEquals(Outcome.LOSE, service.getHistories(me, thisMonth).histories.single().myResult)
    }

    /**
     * 🔴 `isChallenger` 를 보지 않는 유일한 값이다. `LOSE` 로 **접지 않는다** — 접으면 앱이
     * "둘 다 못 했다" 를 인증 상태로 역산해야 하는데, 이 응답에는 인증 상태 필드가 아예 없다.
     */
    @Test
    fun `BOTH_LOSE 는 양쪽 모두에게 BOTH_LOSE 다`() {
        challenges.put(
            completed(id = 150L, challengerId = me, date = LocalDate.of(2026, 8, 6), result = ChallengeResult.BOTH_LOSE),
        )

        assertEquals(Outcome.BOTH_LOSE, service.getHistories(me, thisMonth).histories.single().myResult)
        assertEquals(Outcome.BOTH_LOSE, service.getHistories(other, thisMonth).histories.single().myResult)
    }

    @Test
    fun `DRAW 도 양쪽 모두에게 DRAW 다`() {
        challenges.put(
            completed(id = 151L, challengerId = me, date = LocalDate.of(2026, 8, 6), result = ChallengeResult.DRAW),
        )

        assertEquals(Outcome.DRAW, service.getHistories(me, thisMonth).histories.single().myResult)
        assertEquals(Outcome.DRAW, service.getHistories(other, thisMonth).histories.single().myResult)
    }

    // ─── 8. 미션 뒤집기 / 닉네임 / 빈 목록 ────────────────────────

    @Test
    fun `내가 opponent 면 미션이 내 시점으로 뒤집힌다`() {
        challenges.put(
            completed(id = 160L, challengerId = other, date = LocalDate.of(2026, 8, 7), result = ChallengeResult.OPPONENT_WIN),
        )

        val view = service.getHistories(me, thisMonth).histories.single()

        assertEquals("상대 미션", view.myMission)
        assertEquals("챌린저 미션", view.opponentMission)
        assertEquals("민수", view.opponentNickname)
        assertEquals("커피 사기", view.bet)
        assertEquals(Outcome.WIN, view.myResult, "내가 opponent 인데 OPPONENT_WIN 이면 내 승리다")
    }

    /**
     * 🔴 탈퇴자는 row 가 남고 닉네임이 `"탈퇴한 사용자"` 로 익명화돼 있다 (mypage T-B2) —
     * **서비스는 아무것도 분기하지 않고 그 값을 그대로 흘린다.** 여기에 특별 표시를 넣지 않는
     * 것이 design 의 결정이다(증거 보존 화면에서 *"이 기록은 무효"* 로 읽힌다).
     */
    @Test
    fun `탈퇴한 상대의 익명화된 닉네임이 그대로 나간다`() {
        users.put(user(other, "탈퇴한 사용자", status = UserStatus.DELETED))
        challenges.put(completed(id = 170L, challengerId = me, date = LocalDate.of(2026, 8, 8)))

        assertEquals("탈퇴한 사용자", service.getHistories(me, thisMonth).histories.single().opponentNickname)
    }

    /** row 자체가 없는 것은 탈퇴가 아니라 데이터 이상이다 — 그때만 fallback 이 걸린다. */
    @Test
    fun `상대 row 가 아예 없으면 알 수 없음이다`() {
        challenges.put(completed(id = 171L, challengerId = me, date = LocalDate.of(2026, 8, 8)))
        users.clear()

        assertEquals("(알 수 없음)", service.getHistories(me, thisMonth).histories.single().opponentNickname)
    }

    /**
     * 빈 달은 **정상**이다 — 빈 달을 건너뛰지 않는 것이 확정된 동작이다. 그리고 이때는
     * users 조회를 아예 걸지 않는다.
     */
    @Test
    fun `그 달에 기록이 없으면 빈 목록이고 users 를 조회하지 않는다`() {
        challenges.put(completed(id = 180L, challengerId = me, date = LocalDate.of(2026, 6, 1)))

        assertTrue(service.getHistories(me, YearMonth.of(2026, 7)).histories.isEmpty())
        assertEquals(0, users.findAllByIdsCount, "빈 달에서는 상대를 조회할 이유가 없다")
    }

    /** 🔴 카드가 몇 장이든 users 조회는 **1회**다 (N+1 회피가 이 서비스의 값이다). */
    @Test
    fun `카드가 여러 장이어도 users 조회는 한 번이다`() {
        challenges.put(completed(id = 190L, challengerId = me, date = LocalDate.of(2026, 8, 1)))
        challenges.put(completed(id = 191L, challengerId = other, date = LocalDate.of(2026, 8, 2)))
        challenges.put(completed(id = 192L, challengerId = me, date = LocalDate.of(2026, 8, 3)))

        assertEquals(3, service.getHistories(me, thisMonth).histories.size)
        assertEquals(1, users.findAllByIdsCount)
    }

    /** 남의 챌린지는 보이지 않는다 — 당사자 축이 조회 조건에도 하한 계산에도 들어 있다. */
    @Test
    fun `내가 당사자가 아닌 챌린지는 보이지도 하한을 밀지도 않는다`() {
        val stranger = 99L
        challenges.put(
            completed(id = 200L, challengerId = stranger, date = LocalDate.of(2026, 1, 9)).copy(opponentId = 98L),
        )
        challenges.put(completed(id = 201L, challengerId = me, date = LocalDate.of(2026, 8, 9)))

        val page = service.getHistories(me, thisMonth)

        assertEquals(listOf(201L), page.histories.map { it.challengeId })
        assertEquals(
            YearMonth.of(2026, 8),
            page.firstArchivedMonth,
            "남의 1월 챌린지가 내 하한을 1월로 끌어내리면 안 된다",
        )
    }

    // ─── fixtures ────────────────────────────────────────────────

    private fun user(id: Long, nickname: String, status: UserStatus = UserStatus.ACTIVE) = User(
        id = id,
        kakaoId = if (status == UserStatus.DELETED) null else 9000_000L + id,
        nickname = nickname,
        profileImageUrl = null,
        phoneNumber = null,
        phoneVerified = false,
        fcmToken = null,
        status = status,
        createdAt = LocalDateTime.of(2026, 1, 1, 0, 0),
        updatedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
    )

    /** challenger 가 [challengerId], 상대는 자동으로 나머지 한 명이다 (1:1 대결). */
    private fun completed(
        id: Long,
        challengerId: Long,
        date: LocalDate,
        result: ChallengeResult = ChallengeResult.CHALLENGER_WIN,
        completedAt: LocalDateTime = date.plusDays(1).atTime(0, 5),
    ) = Challenge(
        id = id,
        challengerId = challengerId,
        opponentId = if (challengerId == me) other else me,
        challengerMission = "챌린저 미션",
        opponentMission = "상대 미션",
        betContent = "커피 사기",
        challengeDate = date,
        deadline = date.plusDays(1).atStartOfDay(),
        status = ChallengeStatus.COMPLETED,
        result = result,
        createdAt = date.atTime(9, 0),
        completedAt = completedAt,
    )

    private fun notCompleted(
        id: Long,
        challengerId: Long,
        status: ChallengeStatus,
        date: LocalDate = LocalDate.of(2026, 8, 15),
    ) = completed(id, challengerId, date).copy(
        status = status,
        // COMPLETED 가 아니면 판정 결과가 없다 (계약 §1).
        result = null,
        completedAt = null,
    )

    // ─── in-memory fakes (중첩 — 패키지 내 이름 충돌 회피) ─────────

    /**
     * 실제 구현과 **같은 필터·정렬·MIN** 을 지킨다 — 어긋나면 이 테스트가 거짓말을 한다.
     *
     * 범위는 반개구간 `[monthStart, nextMonthStart)`, 정렬은 `challengeDate DESC` 다음
     * `id DESC` 다. `completedAt` 은 일부러 **어디에도 쓰지 않는다** — 그게 이 목록의 결정
     * 사항이기 때문이다.
     */
    private class FakeHistoryStore : ChallengeRepository {
        private val store = LinkedHashMap<Long, Challenge>()

        fun put(c: Challenge) {
            store[c.id!!] = c
        }

        private fun completedOf(userId: Long): List<Challenge> = store.values
            .filter { it.challengerId == userId || it.opponentId == userId }
            .filter { it.status == ChallengeStatus.COMPLETED }

        override fun findCompletedHistoryByUserInMonth(
            userId: Long,
            monthStart: LocalDate,
            nextMonthStart: LocalDate,
        ): List<Challenge> = completedOf(userId)
            .filter { !it.challengeDate.isBefore(monthStart) && it.challengeDate.isBefore(nextMonthStart) }
            .sortedWith(compareByDescending<Challenge> { it.challengeDate }.thenByDescending { it.id })

        override fun findEarliestCompletedChallengeDate(userId: Long): LocalDate? =
            completedOf(userId).minOfOrNull { it.challengeDate }

        override fun findById(id: Long): Challenge? = store[id]

        override fun findActiveByUser(userId: Long, statuses: Collection<ChallengeStatus>): List<Challenge> =
            emptyList()

        /** 홈 목록 전용 — 이 테스트가 다루는 경로가 아니다 (`ActiveChallengeServiceTest` 소관). */
        override fun findRecentlyCompletedByUser(userId: Long, since: LocalDateTime): List<Challenge> = emptyList()

        override fun findReceivedPending(opponentId: Long, now: LocalDateTime): List<ReceivedChallenge> = emptyList()

        /** 알림 목록의 `referencePending` 전용 — 보관함 경로는 부르지 않는다. */
        override fun findPendingReceivedIdsIn(
            opponentId: Long,
            ids: Collection<Long>,
            now: LocalDateTime,
        ): Set<Long> = error("보관함 서비스가 알림용 응답 대기 판정을 시도했다")

        override fun findDueByStatus(statuses: Collection<ChallengeStatus>, now: LocalDateTime): List<Challenge> =
            emptyList()

        override fun findConflicting(
            userA: Long,
            userB: Long,
            challengeDate: LocalDate,
            statuses: Collection<ChallengeStatus>,
        ): List<Challenge> = emptyList()

        /** 이 서비스는 read-only 다 — 저장 경로를 타면 그 자체가 버그다. */
        override fun save(challenge: Challenge): Challenge = error("조회 전용 서비스가 저장을 시도했다")

        override fun saveAndFlush(challenge: Challenge): Challenge = error("조회 전용 서비스가 저장을 시도했다")

        override fun deleteById(id: Long) = error("조회 전용 서비스가 삭제를 시도했다")

        override fun deletePendingSentBy(userId: Long): Int = error("조회 전용 서비스가 삭제를 시도했다")
    }

    /** [findAllByIdsCount] 로 *"카드 수와 무관하게 1회"* 를 단언한다. */
    private class FakeHistoryUsers : UserRepository {
        private val store = LinkedHashMap<Long, User>()

        var findAllByIdsCount = 0
            private set

        fun put(u: User) {
            store[u.id!!] = u
        }

        fun clear() = store.clear()

        override fun findAllByIds(ids: Collection<Long>): Map<Long, User> {
            findAllByIdsCount++
            return ids.mapNotNull { store[it] }.associateBy { it.id!! }
        }

        override fun findById(id: Long): User? = store[id]

        override fun findByKakaoId(kakaoId: Long): User? = store.values.firstOrNull { it.kakaoId == kakaoId }

        override fun save(user: User): User = error("조회 전용 서비스가 저장을 시도했다")

        override fun updateRefreshTokenHash(userId: Long, hash: String?, issuedAt: LocalDateTime?) =
            error("조회 전용 서비스가 토큰을 건드리려 했다")

        override fun clearFcmTokenOwnedByOthers(fcmToken: String, exceptUserId: Long): Int =
            error("조회 전용 서비스가 FCM 토큰을 건드리려 했다")

        override fun updateFcmToken(userId: Long, fcmToken: String?): Int =
            error("조회 전용 서비스가 FCM 토큰을 건드리려 했다")

        override fun anonymizeForWithdrawal(userId: Long, anonymizedNickname: String, now: LocalDateTime): Int =
            error("조회 전용 서비스가 익명화를 시도했다")
    }

    private companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
