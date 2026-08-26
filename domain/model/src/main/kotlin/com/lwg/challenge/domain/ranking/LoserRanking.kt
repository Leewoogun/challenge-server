package com.lwg.challenge.domain.ranking

/**
 * 개돼지 랭킹(기획 §3.4)의 정렬 전 원재료 1건 — 사용자 한 명의 누적 전적 일부.
 *
 * 🔴 이 타입에는 **"row 없음" 상태가 없다.** `user_stats` 에 행이 없는 사용자(= 챌린지를 한 번도
 * 안 한 사람)는 repository 가 SQL `COALESCE` 로 0 을 채워 넘긴다. 그래서 [losses] 등이 nullable
 * 이 아니고, 여기서 다시 "행이 있었나" 를 분기할 필요도 없다 — 0 은 폴백이 아니라 이미 정규화된
 * 사실이다. `GET /record` 의 `UserRecord.empty` 와 같은 값 정의다.
 *
 * @property totalChallenges 패배율의 분모. [lossRateOf] 계산에 쓰이고, 응답에도 그대로 실린다
 *   ([LoserRankingEntry.totalChallenges] 참조).
 * @property currentLossStreak 가장 최근 결과 시점부터 패배만 연속된 횟수 (`UserRecord` 정의 그대로).
 */
data class LoserStat(
    val userId: Long,
    val nickname: String,
    val profileImageUrl: String?,
    val losses: Int,
    val totalChallenges: Int,
    val currentLossStreak: Int,
)

/**
 * 순위 부여가 끝난 랭킹 1건. 앱이 그대로 그리는 값이다.
 *
 * @property rank 1부터 **연속 고유값**. 공동 순위가 없다 ([rankedAsLosers] 참조).
 * @property lossRate 0~100 정수 %. 서버가 계산하고 서버가 정렬 기준으로 쓴 값 — 앱이 재계산하지 않는다.
 * @property isMe 한 목록에 정확히 1건만 true. 나는 항상 포함되므로 반드시 1건 있다.
 * @property totalChallenges 누적 챌린지 횟수. 🔴 **정렬 키가 아니다** — 표시 분기 전용이다.
 *   챌린지를 한 번도 안 한 사람과 전승한 사람은 `losses = 0, lossRate = 0` 으로 **글자 하나까지
 *   같은 행**이 된다. 정반대 상태인데 화면이 구분할 수단이 없어서 이 값을 내보낸다 —
 *   앱은 `== 0` 이면 캡션을 "아직 기록 없음" 으로 통째 대체한다.
 *   🔴 **이 값으로 [lossRate] 를 재계산하지 마라.** 서버가 반올림한 정수를 **정렬 키로도** 쓰고
 *   있어서([rankedAsLosers]), 앱이 다시 나누면 표시와 정렬이 갈릴 수 있다.
 *   불리언(`hasRecord`)이 아니라 `Int` 인 이유도 같다 — 불리언은 "기록이 있다" 의 정의를 서버가
 *   규칙으로 하나 더 소유하게 만든다. `totalChallenges` 는 규칙이 아니라 **사실**이다.
 */
data class LoserRankingEntry(
    val rank: Int,
    val userId: Long,
    val nickname: String,
    val profileImageUrl: String?,
    val isMe: Boolean,
    val losses: Int,
    val lossRate: Int,
    val currentLossStreak: Int,
    val totalChallenges: Int,
)

/**
 * 패배율(%)을 0~100 정수로 계산한다.
 *
 * ## 🔴 0 ÷ 0 이 `null` 이 아니라 `0` 인 이유
 *
 * 챌린지를 한 번도 안 한 사용자는 분모가 0 이다. 여기서 `null` 을 돌려주면 두 가지가 깨진다.
 * 하나는 앱이 "표시 안 함" 과 "0%" 를 매번 분기해야 한다는 것이고, 다른 하나가 더 나쁜데 —
 * **정렬 키가 null 이 되어 순서 정의 자체가 흔들린다.** 진 적이 없으면 패배율은 0% 다.
 * 이건 폴백이 아니라 사실 진술이다.
 *
 * ## 왜 `Math.round` 인가
 *
 * 반올림 방식이 HALF_UP 이어야 앱/기획이 기대하는 "49.5% → 50%" 가 나온다. `Math.round` 는
 * 양수 구간에서 정확히 HALF_UP 이고, 패배율은 정의상 음수가 될 수 없다.
 */
fun lossRateOf(losses: Int, totalChallenges: Int): Int {
    if (totalChallenges == 0) return 0
    return Math.round(losses * 100.0 / totalChallenges).toInt()
}

/**
 * 원재료 목록을 패배 기준으로 줄 세우고 1부터 연속 고유 `rank` 를 부여한다.
 *
 * 정렬 키 4개, 순서대로:
 * ```
 * losses DESC → currentLossStreak DESC → lossRate DESC → userId ASC
 * ```
 *
 * ## 🔴 `lossRate` 가 1차 키가 아닌 이유
 *
 * 1차로 두면 **1전 1패(100%)가 25패(78%)를 제치고 개돼지왕**이 된다. 기획 서사("많이 진 놈이
 * 대장")의 정반대 결과다. 기획 정의가 축을 나열한 순서("총 패배 수·연패·패율")를 그대로 우선순위로
 * 읽었다 — 패율은 동률을 가르는 **세 번째** 축이지 대표 지표가 아니다.
 *
 * ## 🔴 `userId ASC` 는 장식이 아니라 결정성 타이브레이커다
 *
 * 앞 3키가 모두 같은 두 사람이 실제로 흔하다 — **챌린지를 아직 안 한 친구들은 전부 (0, 0, 0)** 이라
 * 여기 해당한다. 마지막 키가 없으면 PostgreSQL 이 매 호출마다 다른 순서를 줄 수 있고, 그러면
 * 새로고침마다 **포디움 1·2위가 자리를 바꾼다.** 재현 가능한 단위 테스트도 쓸 수 없게 된다.
 *
 * ## 🔴 공동 순위가 없는 이유
 *
 * 위 4키가 전순서(total order)를 만들기 때문에 동점 자체가 성립하지 않는다. Top3 포디움이 1/2/3
 * 자리를 하나씩 그리는 구조라 공동 1위가 오면 레이아웃이 깨지는데, **그 방어를 앱에 시키지 않고
 * 서버가 원천 차단**한다.
 *
 * ## 왜 SQL `ORDER BY` 가 아니라 순수 함수인가
 *
 * 목록 길이가 "친구 수 + 1" 로 묶여 있어 in-memory 정렬로 충분하다. 그리고 `UserRecord.applying`
 * 과 같은 이유로 — 규칙은 Spring 도 DB 도 모르는 순수 함수여야 단위 테스트로 전부 고정할 수 있다.
 * SQL 문자열 안의 `ORDER BY` 는 그렇게 못 한다.
 *
 * @param me 요청자 userId. 이 값과 일치하는 항목의 `isMe` 가 true 가 된다.
 */
fun List<LoserStat>.rankedAsLosers(me: Long): List<LoserRankingEntry> {
    // 🔴 정렬 키의 lossRate 는 **반올림 후 정수값**이어야 한다. 반올림 전 실수로 정렬하면
    // 같은 퍼센트로 표시되는 두 사람의 순서가 화면에서 설명되지 않는다.
    val rated = map { stat -> stat to lossRateOf(stat.losses, stat.totalChallenges) }

    return rated
        .sortedWith(
            compareByDescending<Pair<LoserStat, Int>> { it.first.losses }
                .thenByDescending { it.first.currentLossStreak }
                .thenByDescending { it.second }
                .thenBy { it.first.userId },
        )
        .mapIndexed { index, (stat, lossRate) ->
            LoserRankingEntry(
                rank = index + 1,
                userId = stat.userId,
                nickname = stat.nickname,
                profileImageUrl = stat.profileImageUrl,
                isMe = stat.userId == me,
                losses = stat.losses,
                lossRate = lossRate,
                currentLossStreak = stat.currentLossStreak,
                totalChallenges = stat.totalChallenges,
            )
        }
}
