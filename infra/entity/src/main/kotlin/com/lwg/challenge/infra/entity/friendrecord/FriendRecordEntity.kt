package com.lwg.challenge.infra.entity.friendrecord

import com.lwg.challenge.domain.friendrecord.FriendRecord
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * `friend_records` 테이블 JPA 매핑 (V1__init.sql — 이번에 처음 코드가 붙는다).
 *
 * PK 가 `user_stats` 와 다르다: 저쪽은 `user_id` 자체가 PK 라 `@GeneratedValue` 가 없지만,
 * 이 테이블은 `BIGSERIAL id` 가 PK 이고 `(user_id, friend_id)` 는 UNIQUE 제약이다.
 * 그래서 `IDENTITY` 전략이 필요하고, **저장 전에 기존 행을 조회해 `id` 를 실어 주지 않으면
 * 새 행을 INSERT 하려다 `uq_friend_records_user_friend` 에 걸린다** —
 * `FriendRecordRepositoryImpl` 이 아니라 호출부(`ChallengeJudgementService`)가 먼저 조회하는 이유다.
 *
 * `updated_at` 컬럼이 없다 (스키마 그대로). `user_stats` 와 달리 `@PreUpdate` 가 없는 이유다.
 *
 * `users` 와의 association 매핑을 두지 않고 FK 를 `Long` 으로만 들고 있는 것은 `ChallengeEntity` /
 * `UserRecordEntity` 와 같은 규약이다 (도메인 순수성 유지).
 */
@Entity
@Table(name = "friend_records")
class FriendRecordEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "friend_id", nullable = false)
    var friendId: Long,

    @Column(name = "wins", nullable = false)
    var wins: Int = 0,

    @Column(name = "losses", nullable = false)
    var losses: Int = 0,

    @Column(name = "draws", nullable = false)
    var draws: Int = 0,
) {

    fun toDomain(): FriendRecord = FriendRecord(
        id = id,
        userId = userId,
        friendId = friendId,
        wins = wins,
        losses = losses,
        draws = draws,
    )

    companion object {
        fun fromDomain(r: FriendRecord): FriendRecordEntity = FriendRecordEntity(
            id = r.id,
            userId = r.userId,
            friendId = r.friendId,
            wins = r.wins,
            losses = r.losses,
            draws = r.draws,
        )
    }
}
