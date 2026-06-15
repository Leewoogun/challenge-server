package com.lwg.challenge.domain.userrecord

/**
 * user_stats 영속성 추상 (전적/record).
 *
 * row 가 없을 수 있다 — 신규 사용자는 첫 챌린지 결과가 나기 전까지 row 자체가 없을 수 있다.
 * 그 경우 호출부에서 [UserRecord.empty] 로 0 채움.
 */
interface UserRecordRepository {

    /** user_id 로 단건 조회. row 가 없으면 null. */
    fun findByUserId(userId: Long): UserRecord?

    /** 저장 (테스트 시드/집계 작업용). */
    fun save(record: UserRecord): UserRecord
}
