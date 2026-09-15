package com.lwg.challenge.service.challenge

import com.lwg.challenge.domain.challenge.Challenge
import com.lwg.challenge.domain.challenge.ChallengeRepository
import com.lwg.challenge.domain.challenge.ChallengeVerdict
import com.lwg.challenge.domain.challenge.Outcome
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

/**
 * 계약서 보관함 — **한 달치** 조회 단일 read 트랜잭션 (mypage T-B1, 2026-08-28 월 단위 개정).
 *
 * ## 🔴 1차의 "전체 반환 + 앱이 월 그룹핑" 을 폐기했다
 *
 * 사용자 확정(2026-08-28): **월 1칸씩 순차 이동, 빈 달도 건너뛰지 않는다. 첫 진입은 항상
 * 이번 달.** 그래서 이 서비스가 하는 일이 하나 늘었다 — 목록만이 아니라 **이동 경계**를
 * 함께 답한다 ([ChallengeHistoryPage.month] 에코 = 상한).
 *
 * ## 🔴 2026-08-28 (2차) — **하한이 wire 에서 사라졌다. `<` 는 항상 활성이다**
 *
 * 사용자 실기 확인 후 재확정: **기록이 없어도 과거로 갈 수 있고 빈 달 화면을 보여준다.**
 * 계기는 실기에서 종료 기록이 전부 이번 달이라 **하한(최초 기록월) = 상한(이번 달)** 이 되어
 * 양쪽 화살표가 동시에 죽었고, 카드는 보이는데 아무것도 안 움직여 **사용자가 "고장"으로 읽은
 * 것**이다. 🔴 설계도 계약도 정확히 동작했지만 **그 정확함이 신규 사용자에게 고장으로 보였다.**
 *
 * 🔴 **서버 코드 변경이 0이다** — 이 서비스는 애초에 하한을 강제한 적이 없다. 거부하는 것은
 * **미래 달 하나뿐**이고 과거는 어느 달이든 빈 목록으로 정상 응답해 왔다. 하한은 처음부터
 * **앱이 [ChallengeHistoryPage.firstArchivedMonth] 로 스스로 걸던 규칙**이라, 그 규칙을 앱에서
 * 걷어내는 것으로 개정이 끝난다.
 *
 * ⚠️ **1차 하한 논거는 폐기가 아니라 수단이 바뀌었다** — *"보관함은 '이게 네 기록 전부다'라고
 * 주장하는 화면인데 하한이 없으면 그 주장을 할 수 없다."* 그 요구는 **차단이 아니라 주장할
 * 수단**이었고, design 이 빈 달 부제(*"첫 기록은 YYYY년 M월이에요"*)로 **차단 → 고지**로 옮겼다.
 * `firstArchivedMonth` 가 그 고지의 기준이 된다.
 *
 * ## 🔴 상한이 **서버 값**이어야 하는 이유
 *
 * 앱이 상한("이번 달")을 **기기 시계**로 계산하면 서버 값과 **다른 시계**에서 나온다. 이
 * 프로젝트는 이미 `DeadlineType` 에서 *"기기 시계 조작·타임존 불일치를 차단하고 마감 기준을
 * 서버로 일원화"* 를 규약으로 박아 뒀다(`KstDeadlineCalculator` KDoc). 같은 판단이다.
 *
 * 그래서 [getHistories] 의 `requestedMonth` 가 **nullable** 이다 — 앱의 첫 호출에는 월이 없고,
 * 그 응답의 에코가 곧 앱이 아는 "이번 달"이 된다. **앱은 시계를 아예 읽지 않는다.**
 *
 * ## 쿼리 3개
 *
 *  1. `MIN(challenge_date)` — 빈 상태 2종 판별용 (`findEarliestCompletedChallengeDate`)
 *  2. 그 달의 challenges — `challenge_date DESC, id DESC`
 *  3. users 일괄 조회 (상대방 N 명 닉네임) — **그 달이 비면 걸지 않는다**
 *
 * 카드에 인증 상태를 그리지 않아 verifications 를 조회하지 않는다.
 *
 * 🔴 **그 근거였던 문장은 기권이 생기면서 더는 참이 아니다** (challenge-forfeit, 2026-09-05).
 * 원래 근거는 *"결과 4개가 양측 인증 상태를 유일하게 결정하므로 결과 하나가 인증 상태 두 개의
 * 정보를 전부 담는다"* 였는데, 기권이 들어오면서 [ChallengeVerdict.decide] 의 대응이
 * **일대일이 아니게 됐다** — `OPPONENT_WIN` 은 이제 *"challenger 만 미인증"* 일 수도,
 * *"challenger 가 기권했고 상대도 미인증"* 일 수도 있다.
 *
 * 🔑 **그래도 조회하지 않는 결론은 그대로다. 근거만 바뀐다** — 보관함 카드는 애초에 인증
 * 상태를 그리지 않기 때문이다(역산할 이유가 없다). 🔴 **언젠가 카드에 인증 상태를 그리게
 * 되면 `result` 로 역산하지 말고 verifications 를 조회해라.** 역산은 이제 틀린 답을 낸다.
 */
@Service
class ChallengeHistoryService(
    private val userRepository: UserRepository,
    private val challengeRepository: ChallengeRepository,
    private val clock: Clock,
) {

    /**
     * @param requestedMonth 조회할 월. **null 이면 이번 달**(= 앱의 첫 진입). 앱이 이번 달을
     *   직접 계산해 보내지 않아도 되게 하려고 nullable 이다 — 그래야 "첫 진입은 항상 이번 달"이
     *   기기 시계와 무관하게 성립한다.
     * @throws SnackbarException 미래 달을 요청한 경우. 정상 경로에서는 발생하지 않는다 —
     *   앱은 첫 응답의 `month` 에코를 상한으로 삼아 `>` 를 비활성화한다. **안전망이다.**
     */
    @Transactional(readOnly = true)
    fun getHistories(userId: Long, requestedMonth: YearMonth?): ChallengeHistoryPage {
        val currentMonth: YearMonth = YearMonth.from(LocalDate.now(clock))
        val month: YearMonth = requestedMonth ?: currentMonth

        // 🔴 상한 초과는 조회 전에 끊는다. 조용히 이번 달로 clamp 하면 응답의 month 에코가
        //    요청과 달라져, 앱 화면에서 **월 표시와 카드 내용이 어긋난다.**
        if (month > currentMonth) {
            throw SnackbarException("아직 오지 않은 달이에요")
        }

        // 🔴 **빈 상태 2종 판별 전용**이다. 하한이 아니다 — [ChallengeHistoryPage.firstArchivedMonth] KDoc.
        //    기록 0건이면 null 이고, 그 자체가 "보관함이 통째로 비었다" 의 신호다.
        val firstArchivedMonth: YearMonth? = challengeRepository
            .findEarliestCompletedChallengeDate(userId)
            ?.let { YearMonth.from(it) }

        val challenges: List<Challenge> = challengeRepository.findCompletedHistoryByUserInMonth(
            userId = userId,
            monthStart = month.atDay(1),
            // 반개구간이라 말일 계산이 없다 — 2월·윤년 분기가 생길 자리를 없앤다.
            nextMonthStart = month.plusMonths(1).atDay(1),
        )

        return ChallengeHistoryPage(
            month = month,
            firstArchivedMonth = firstArchivedMonth,
            histories = toViews(userId, challenges),
        )
    }

    // ─── helpers ─────────────────────────────────────────────────

    /** 카드 7필드의 산출 규칙은 1차 이후 **아무것도 바뀌지 않았다.** */
    private fun toViews(userId: Long, challenges: List<Challenge>): List<ChallengeHistoryView> {
        // 그 달이 비면 users 조회를 아예 걸지 않는다 — 조회할 상대가 없다.
        if (challenges.isEmpty()) return emptyList()

        val opponentIds: Set<Long> = challenges.map { c ->
            if (c.challengerId == userId) c.opponentId else c.challengerId
        }.toSet()
        val opponentsById = userRepository.findAllByIds(opponentIds)

        return challenges.map { c ->
            val challengeId: Long = c.id ?: error("영속화된 challenge 의 id 는 null 일 수 없습니다")
            val isChallenger: Boolean = c.challengerId == userId
            val opponentUserId: Long = if (isChallenger) c.opponentId else c.challengerId
            // opponentMission 은 V5 이후 nullable — 상대가 수락하며 채운다. 여기서 다루는 것은
            // COMPLETED(= 수락을 거친 상태)뿐이라 항상 non-null 이지만, 수동 시드 등으로 비어
            // 있는 row 가 섞여도 응답이 깨지지 않도록 빈 문자열로 대체한다
            // (`ActiveChallengeService` 와 같은 방어).
            val acceptedOpponentMission: String = c.opponentMission.orEmpty()
            // 🔴 탈퇴한 사용자는 row 가 남고 nickname 이 "탈퇴한 사용자" 로 익명화돼 있다
            //    (mypage T-B2 — `WithdrawalService.ANONYMIZED_NICKNAME`). 그래서 여기 fallback 이
            //    걸리는 것은 탈퇴가 아니라 **row 자체가 없는 데이터 이상**뿐이다.
            val opponentNickname: String = opponentsById[opponentUserId]?.nickname ?: UNKNOWN_OPPONENT
            // COMPLETED 만 조회하므로 판정이 끝나 있다 — result 가 null 이면 데이터가 깨진 것이다.
            val result = c.result ?: error("COMPLETED 인 challenge 의 result 는 null 일 수 없습니다: id=$challengeId")

            ChallengeHistoryView(
                challengeId = challengeId,
                challengeDate = c.challengeDate,
                opponentNickname = opponentNickname,
                myMission = if (isChallenger) c.challengerMission else acceptedOpponentMission,
                opponentMission = if (isChallenger) acceptedOpponentMission else c.challengerMission,
                bet = c.betContent,
                // 🔴 뒤집기 규칙을 여기서 새로 쓰지 않는다. `ChallengeVerdict.outcomeFor` 호출이
                //    전부다 — 판정·환산 규칙은 그 object 한 곳에만 산다 (ChallengeVerdict KDoc).
                //    특히 BOTH_LOSE 는 뒤집지 않고 Outcome.BOTH_LOSE 로 그대로 나가는데,
                //    직접 쓰면 LOSE 로 접어 버리기 쉬운 값이다 (Outcome KDoc).
                myResult = ChallengeVerdict.outcomeFor(result, isChallenger),
            )
        }
    }

    companion object {
        /** `ActiveChallengeService` / `ChallengeDetailService` 와 같은 문구를 쓴다. */
        private const val UNKNOWN_OPPONENT = "(알 수 없음)"
    }
}

/**
 * 보관함 한 달치 응답 — 카드 목록 **과 이동 경계**.
 *
 * ## 🔴 경계를 **절대값 1개**로 준다. 불리언 2개(`hasPrevious`/`hasNext`)는 기각됐다
 *
 * 2026-08-28 개정 초안은 불리언 2개였다. archive-month-design·archive-month-mobile 이 **같은
 * 반례**로 기각했고 그쪽이 옳다 — 상세는 [firstArchivedMonth] KDoc.
 */
data class ChallengeHistoryPage(
    /**
     * 실제로 조회한 월. 요청이 없었으면 **서버가 정한 이번 달**이다.
     *
     * 앱은 두 가지로 쓴다:
     * 1. 🔴 **상한.** 파라미터 없이 부른 **첫 응답의 이 값이 곧 "이번 달"** 이다. 앱은 기기
     *    시계를 아예 읽지 않는다 — 기기 타임존이 KST 가 아니면 월말·월초에 서버와 한 달
     *    어긋나고, 그 증상이 *"`>` 가 잠긴 채 이번 달 기록이 안 보인다"* 로 나타난다.
     * 2. **응답 경합 방어.** 빈 달을 건너뛰지 않는 확정 때문에 **화살표 연타가 정상 사용**이고
     *    따라서 요청 겹침도 정상이다. 늦게 도착한 옛 응답이 현재 월 표시 아래 다른 달 카드를
     *    그리는 것을 앱이 이 값으로 막는다. 1차(1회 조회)에는 없던 표면이다.
     */
    val month: YearMonth,
    /**
     * 이 사용자의 **가장 오래된 `COMPLETED` 기록의 월.** 종료 기록이 0건이면 **null**.
     *
     * 🔴 **용도는 빈 상태 2종 판별 하나뿐이다** — `null` 이면 *"보관함이 통째로 비었다"*(기록 전무
     * 화면 + *"챌린지 만들기"* CTA), non-null 이면 *"이 달만 비었다"*(빈 달 화면, CTA 없음).
     * `histories: []` 만으로는 그 둘이 안 갈리고 **다른 출처가 없다.**
     *
     * ## 🔴 이 값으로 `<` 를 막지 마라 (2026-08-28 2차 개정)
     *
     * v3 까지는 이게 **하한**이었다. 사용자가 그 규칙을 폐기했다 — 실기에서 기록이 전부 이번
     * 달이라 **하한 = 상한 = 이번 달**이 되어 양쪽 화살표가 동시에 죽었고 **"고장"으로 읽혔다.**
     * 🔴 **이제 하한 자체가 없다 — `<` 는 항상 활성이다.**
     *
     * ⚠️ **월 값이 그대로 남아 있어 다시 하한으로 쓰기 쉽다 — 그게 방금 폐기한 규칙이다.**
     * 필드를 지우지 않는 이유는 위 판별 용도가 살아 있고 대체 출처가 없기 때문이지,
     * 경계로 쓰라는 뜻이 아니다.
     *
     * ## 🔴 왜 `hasPrevious: Boolean` 이 아닌가 — 불리언은 연타에서 경계를 못 지킨다
     *
     * 확정 사항 둘이 맞물린다: 사용자 확정 *"빈 달도 건너뛰지 않는다"* → 하한까지 가려면
     * `<` 를 **여러 번 눌러야 하고 연타가 정상 사용**이다. design §2.5.4.3-① → **월 전환 중에도
     * 화살표는 계속 눌려야 한다**(응답을 기다리며 입력을 잠그면 *"하나씩 지나간다"* 가 매 칸
     * 네트워크 왕복만큼 느려져 사실상 불가능해진다).
     *
     * 불리언은 **그 달의 응답이 와야** 그 달의 경계를 안다. 하한이 5월인 사용자가 6월에서:
     * ```
     * 탭 ①  →  5월 요청 (in flight). 5월이 하한인지 아직 모른다
     * 탭 ②  →  🔴 4월. 존재할 수 없는 달에 사용자가 서 있다
     * (4월 응답) →  hasPrevious=false 로 그제서야 "여기가 끝"
     * ```
     * **경계를 넘은 뒤에 경계를 통보받는다.** 하한을 만든 목적이 정확히 *"확정적으로 빈 달의
     * 사막으로 걸어 들어가지 않게 한다"* 였는데 불리언은 그걸 한 탭 늦게 한다. 대칭으로 `>`
     * 연타는 **미래 달 요청 → code 700** 을 만들어 **정상 조작이 오류 화면**이 된다.
     *
     * ⚠️ **초안이 이걸 놓친 이유를 남긴다** — *"불리언이 신호가 하나라 더 적다"* 고 봤는데,
     * `result`+`myResult` 처럼 **두 표현이 경쟁하는** 경우와 혼동한 것이다. 여기서 불리언은
     * 절대값의 **손실 있는 투영**이지 더 적은 신호가 아니다. 절대값 하나만 주므로 경쟁하는
     * 신호도 없다.
     *
     * ## 🔴 nullable 인 것이 신호다
     *
     * `null` = *"보관함이 통째로 비었다"*(신규 사용자). 앱은 이걸로 **빈 달 화면**과
     * **기록 전무 화면**을 가른다 — 후자만 *"챌린지 만들기"* CTA 를 띄운다(기록을 찾는 중인
     * 사용자를 보관함 밖으로 내보내면 안 된다).
     *
     * ## 이 값은 과거로 밀리지 않는다
     *
     * `challengeDate` 가 소급 생성되지 않으므로(`KstDeadlineCalculator`: 생성 시점의 오늘 또는
     * 내일) 한 번 정해지면 내려가지 않는다. 매 응답에 실어도 낭비가 아니다.
     */
    val firstArchivedMonth: YearMonth?,
    /** 그 달의 카드. **빌 수 있다** — 빈 달을 건너뛰지 않는 것이 사용자 확정 동작이다. */
    val histories: List<ChallengeHistoryView>,
)

/**
 * 보관함 카드 한 건 — **판정이 끝난** 챌린지다.
 *
 * `myMission` / `opponentMission` 은 현재 사용자가 challenger 인지 opponent 인지에 따라 자동 분기.
 *
 * ## 🔴 필드가 7개인 것이 확정이다 (design §7-④) — 아래 둘을 여기에 더하지 마라
 *
 * - **역할 기준 `result` 원본을 담지 않는다.** `/challenges/active` 는 `result` 와 `myResult` 를
 *   둘 다 주지만 여기는 [myResult] 뿐이다. 보관함 카드가 그리는 것은 *"내가 이겼나"* 하나이고,
 *   둘 다 내려가면 **앱이 어느 쪽을 믿을지 고르게 되며 그 선택이 화면마다 갈린다.**
 *   [Outcome.BOTH_LOSE] 가 이미 있어 *"양쪽 다 졌다"* 도 [myResult] 만으로 표현된다.
 * - **인증 상태 2필드를 담지 않는다.** 카드가 인증 pill 을 그리지 않기 때문이다.
 *   [ChallengeHistoryService] 가 verifications 를 조회하지 않는 것이 이 결정의 결과다.
 *
 *   ⚠️ **원래 근거는 *"결과 4개가 양측 인증 상태를 유일하게 결정하므로 정보량이 0인 필드 2개"*
 *   였고, 기권이 그 대응을 일대일이 아니게 만들어 더는 참이 아니다** (challenge-forfeit,
 *   2026-09-05). 결론은 그대로이되 근거가 바뀌었다 — 자세한 것은 [ChallengeHistoryService] KDoc.
 *
 * 🔴 **2026-08-28 월 단위 개정에서 이 7필드는 하나도 바뀌지 않았다.** 바뀐 것은 이 카드들이
 * 담기는 그릇([ChallengeHistoryPage])뿐이다.
 */
data class ChallengeHistoryView(
    val challengeId: Long,
    /**
     * 챌린지 날짜. **조회 범위 키이자 정렬 키**다.
     *
     * 🔴 1차에서는 *"앱이 앞 7글자로 잘라 쓰는 월 그룹 키"* 이기도 했다. 월 단위 조회가 되면서
     * 그 용도는 사라졌다 — 응답 하나가 이미 한 달치다.
     */
    val challengeDate: LocalDate,
    /** 탈퇴자는 `"탈퇴한 사용자"`(익명화된 값 그대로), row 자체가 없으면 `"(알 수 없음)"`. */
    val opponentNickname: String,
    val myMission: String,
    val opponentMission: String,
    val bet: String,
    /**
     * 내 시점 결과 — `WIN` / `LOSE` / `DRAW` / `BOTH_LOSE`.
     *
     * 🔴 **non-null 이다.** 이 서비스는 `COMPLETED` 만 조회하므로 판정이 이미 끝나 있다.
     * `ActiveChallengeView.myResult` 가 nullable 인 것과 대비되는 지점인데, 그쪽은 진행 중
     * 카드를 **같은 목록에 섞기 때문**이고 여기는 섞을 것이 없다.
     *
     * 값은 [ChallengeVerdict.outcomeFor] 로 환산한 것이다 — 이 카드의 나머지 필드
     * ([myMission] 등)와 "내 시점" 축을 맞춘다.
     */
    val myResult: Outcome,
)
