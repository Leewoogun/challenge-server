package com.lwg.challenge.service.record

import com.lwg.challenge.domain.userrecord.UserRecord
import com.lwg.challenge.domain.userrecord.UserRecordRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 사용자 누적 전적(record) 단건 조회.
 *
 * row 가 없는 신규 사용자는 [UserRecord.empty] 로 0 채움.
 */
@Service
class RecordService(
    private val userRecordRepository: UserRecordRepository,
) {

    @Transactional(readOnly = true)
    fun getMyRecord(userId: Long): UserRecord =
        userRecordRepository.findByUserId(userId) ?: UserRecord.empty(userId)
}
