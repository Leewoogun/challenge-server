package com.lwg.challenge.domain.friendrecord

import com.lwg.challenge.domain.challenge.Outcome

/**
 * `friend_records` — **방향 있는** 상대 전적 도메인 모델 (challenge-result T-B3, 계약 §3.4).
 *
 * `(user_id, friend_id)` 가 UNIQUE 이고, **방향이 있다.** 챌린지 1건이 `A→B` 와 `B→A` 두 행을
 * 갱신한다. `A→B` 의 `wins` 는 *"A 가 B 를 상대로 이긴 횟수"* 다 — 한 행만 쓰면 B 의 관점을
 * 조회할 때 방향을 뒤집어 읽어야 하고, 그 뒤집기를 잊는 순간 승패가 반대로 보인다.
 *
 * ## 🔴 이 feature 에는 **읽는 wire 가 없다**
 *
 * 지금 이 데이터를 쓰는 화면은 하나도 없다. 테이블 자체는 V1 부터 있었으나 쓰는 코드가 없었고,
 * 이번에 처음으로 채워진다. 소비자는 후속 **개돼지 랭킹** feature 다.
 *
 * 그럼에도 지금 채우는 이유: 전적은 **소급 계산이 불가능**하다. 판정 시점에 안 쌓으면 랭킹이
 * 붙는 날 과거 챌린지의 상대 전적은 영영 0 이다. 반면 지금 쌓아 두는 비용은 판정 트랜잭션에
 * UPSERT 두 번뿐이다.
 *
 * ## 컬럼이 셋뿐이다
 *
 * `wins`/`losses`/`draws` 만 있고 **streak 컬럼이 없다** ([com.lwg.challenge.domain.userrecord.UserRecord]
 * 와 다른 점). 스키마가 그렇고, 상대별 연승은 랭킹이 요구한 적이 없다. 필요해지면 마이그레이션
 * 대상이지 여기서 파생시킬 것이 아니다.
 *
 * `id == null` ⇒ 신규 (BIGSERIAL 이 채운다).
 */
data class FriendRecord(
    val id: Long?,
    val userId: Long,
    val friendId: Long,
    val wins: Int,
    val losses: Int,
    val draws: Int,
) {

    /**
     * 결과 1건을 반영한 **새** 상대 전적.
     *
     * @param outcome **[userId] 관점**의 결과다. `A→B` 행에는 A 의 outcome 을, `B→A` 행에는
     *   B 의 outcome 을 넘겨야 한다 — 같은 값을 두 행에 넘기면 양쪽이 똑같이 이긴 것으로 쌓인다.
     *
     * [com.lwg.challenge.domain.userrecord.UserRecord.applying] 과 달리 시각 인자가 없다.
     * 이 테이블에는 `updated_at` 컬럼 자체가 없다 (V1 스키마).
     *
     * 🔴 [Outcome.BOTH_LOSE] 는 **[Outcome.LOSE] 와 똑같이 `losses` 를 올린다.** 양측 패는
     * `outcomeFor` 가 접지 않고 그대로 넘겨주는 값이라(앱이 "둘 다 졌다" 를 역산하지 않게 하려는
     * 것 — [Outcome] KDoc), 여기서 `LOSE` 만 세면 **양측 패가 아무 데도 안 세어진다.**
     * home-feed 정본이 *"BOTH_LOSE → 양쪽 lose+1"* 로 확정했다 (계약 §3.4).
     * [com.lwg.challenge.domain.userrecord.UserRecord.applying] 과 같은 규칙이다.
     */
    fun applying(outcome: Outcome): FriendRecord = copy(
        wins = if (outcome == Outcome.WIN) wins + 1 else wins,
        losses = if (outcome == Outcome.LOSE || outcome == Outcome.BOTH_LOSE) losses + 1 else losses,
        draws = if (outcome == Outcome.DRAW) draws + 1 else draws,
    )

    companion object {
        /**
         * 아직 맞붙은 적 없는 두 사람의 0 채움 행.
         *
         * `user_stats` 와 같은 이유로 필요하다 — 첫 판정 전까지 row 가 존재하지 않으므로,
         * 조회부가 null 을 만나는 대신 여기서 0 에서 시작하는 행을 만든다.
         */
        fun empty(userId: Long, friendId: Long): FriendRecord = FriendRecord(
            id = null,
            userId = userId,
            friendId = friendId,
            wins = 0,
            losses = 0,
            draws = 0,
        )
    }
}
