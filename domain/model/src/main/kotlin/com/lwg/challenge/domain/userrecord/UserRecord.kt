package com.lwg.challenge.domain.userrecord

import com.lwg.challenge.core.time.KstTime
import com.lwg.challenge.domain.challenge.Outcome
import java.time.LocalDateTime

/**
 * 사용자 누적 전적(record) 도메인 모델.
 *
 * V1__init.sql 의 `user_stats` 테이블(user_id PK)과 매핑. 테이블명은 호환을 위해 유지.
 * 연패 두 컬럼은 V10 에서 추가됐다.
 *
 * row 가 없는 신규 사용자는 [empty] 로 0 채움.
 *
 * ## 🔴 streak 이 **연승/연패 두 쌍**인 이유
 *
 * [currentStreak]/[maxStreak] 은 **연승 전용**, [currentLossStreak]/[maxLossStreak] 은
 * **연패 전용**이다. 한 컬럼의 부호를 섞어(`-2` = 2연패) 둘을 겸하지 않았다 — 홈 StatsBar 가
 * [currentStreak] 을 **"연승"** 라벨로 그리고 `HomeUiState` 가 `> 0` 일 때 `"N🔥"` 로 표기하기
 * 때문에, 음수가 들어가는 순간 화면이 거짓말을 하고 [maxStreak]("최대 연승")의 의미도 무너진다.
 *
 * @property currentLossStreak 가장 최근 결과 시점부터 **패배만** 연속된 횟수. 개돼지
 *   랭킹(기획 §3.4)의 정렬 축 셋(총 패배 수·연패·패율) 중 하나다.
 *   🔴 **지금 이 값을 읽는 화면은 없다** — `GET /record` 응답에도 나가지 않는다. 그럼에도
 *   판정 시점에 채우는 이유는 연패가 **순서 의존**이라 나중에 합계에서 산출할 수 없기
 *   때문이다 (V10 마이그레이션 주석 참조).
 * @property maxLossStreak [currentLossStreak] 의 역대 최고치. 줄지 않는다.
 */
data class UserRecord(
    val userId: Long,
    val totalChallenges: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val currentStreak: Int,
    val maxStreak: Int,
    val currentLossStreak: Int,
    val maxLossStreak: Int,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun empty(userId: Long): UserRecord = UserRecord(
            userId = userId,
            totalChallenges = 0,
            wins = 0,
            losses = 0,
            draws = 0,
            currentStreak = 0,
            maxStreak = 0,
            currentLossStreak = 0,
            maxLossStreak = 0,
            updatedAt = KstTime.now(),
        )
    }
}

/**
 * 결과 1건을 반영한 **새** 전적을 돌려준다 (challenge-result T-B3, 계약 §3.3).
 *
 * ## 왜 서비스가 아니라 도메인에 있나
 *
 * 집계는 "판정 결과를 숫자로 옮기는 규칙" 이고, `ChallengeVerdict` 와 같은 이유로 **한 곳에만**
 * 있어야 한다. 랭킹·정정·재계산 같은 두 번째 호출부가 생겼을 때 서비스 안 `when` 절을 복사하면
 * 그 순간 규칙이 갈린다. Spring 도 DB 도 모르는 순수 함수라 단위 테스트로 전부 고정할 수 있다.
 *
 * ## 🔴 `currentStreak` 은 **연승 전용**이다
 *
 * 모바일 `UserRecord` KDoc 이 *"가장 최근 결과 시점부터 **win 만** 연속된 횟수. lose/draw/both_lose
 * 시 0"* 이라고 이미 정의해 두었고, `HomeUiState` 의 `if (currentStreak > 0) "N🔥"` 표기가 그
 * 정의를 전제하고 있다. **서버는 새 규칙을 만드는 게 아니라 이미 있는 규칙을 이행한다.**
 *
 * 부호를 섞어 연패를 표현하고 싶어질 수 있는데(`-2` = 2연패) 그러면 [maxStreak] 의 의미가
 * 무너지고("최대 연승" 이 아니게 된다) 홈의 🔥 표기도 깨진다. 그래서 연패는 V10 에서
 * [UserRecord.currentLossStreak] / [UserRecord.maxLossStreak] **별도 컬럼**으로 갔다.
 *
 * ## 🔴 `currentLossStreak` 은 **연승 규칙의 정확한 거울상**이다
 *
 * | outcome | `currentStreak` | `currentLossStreak` |
 * |---|---|---|
 * | [Outcome.WIN] | +1 | **0** |
 * | [Outcome.LOSE] | 0 | **+1** |
 * | [Outcome.BOTH_LOSE] | 0 | **+1** |
 * | [Outcome.DRAW] | 0 | **0** |
 *
 * [currentStreak] 이 "win 만 연속" 이고 무승부가 끊는 것처럼, [currentLossStreak] 은
 * "패배만 연속" 이고 **무승부가 끊는다.** 무승부는 패배가 아니므로 "연속 패배" 가 이어질 근거가
 * 없다. 두 규칙이 같은 모양이라야 다음 사람이 한쪽만 보고도 나머지를 맞게 추측한다 —
 * 여기서 무승부만 예외로 두면 그 추측이 조용히 틀린다.
 *
 * [Outcome.BOTH_LOSE] 가 연패를 잇는 것은 그것이 **패배이기 때문**이다. home-feed 정본이
 * *"BOTH_LOSE → 양쪽 lose+1"* 로 이미 패배로 세고 있고(계약 §3.3), [UserRecord.losses] 와
 * [UserRecord.currentLossStreak] 이 서로 다른 사건 집합을 세면 개돼지 랭킹의 두 정렬 축
 * (총 패배 수·연패)이 어긋난다.
 *
 * ## ⚠️ 이름이 `apply` 가 아닌 이유
 *
 * Kotlin stdlib 의 `T.apply { }` 확장과 이름이 겹친다. 겹치면 `record.apply(outcome, now)` 가
 * 무엇을 부르는지 읽는 사람이 매번 멈춰야 하고, 인자 없는 호출로 오타가 나면 stdlib 쪽이
 * 조용히 선택된다. `applying` 은 "새 값을 돌려준다"는 불변 시맨틱도 함께 드러낸다.
 *
 * @param at `updatedAt` 에 기록할 판정 시각. **KST** 여야 한다 (ADR-0010) — 호출부가
 *   `LocalDateTime.now(clock)` 으로 넘긴다. 이 함수가 직접 시각을 취득하지 않는 이유는
 *   양측 두 row 의 `updated_at` 이 같은 값이어야 하기 때문이다.
 */
fun UserRecord.applying(outcome: Outcome, at: LocalDateTime): UserRecord {
    // 🔴 양측 패도 **패배다.** losses 와 연패가 같은 사건 집합을 세야 랭킹의 두 정렬 축이 맞는다.
    val isLoss = outcome == Outcome.LOSE || outcome == Outcome.BOTH_LOSE

    // 승리만 연승을 잇는다. 나머지 셋(패·무·양패)은 전부 0 으로 끊는다.
    val nextStreak = if (outcome == Outcome.WIN) currentStreak + 1 else 0
    // 거울상: 패배(양측 패 포함)만 연패를 잇고, 승리와 **무승부**가 0 으로 끊는다.
    val nextLossStreak = if (isLoss) currentLossStreak + 1 else 0
    return copy(
        totalChallenges = totalChallenges + 1,
        wins = if (outcome == Outcome.WIN) wins + 1 else wins,
        losses = if (isLoss) losses + 1 else losses,
        draws = if (outcome == Outcome.DRAW) draws + 1 else draws,
        currentStreak = nextStreak,
        // 🔴 max 로 감싸지 않으면 연승이 끊길 때 maxStreak 이 0 으로 되돌아간다.
        maxStreak = maxOf(maxStreak, nextStreak),
        currentLossStreak = nextLossStreak,
        maxLossStreak = maxOf(maxLossStreak, nextLossStreak),
        updatedAt = at,
    )
}
