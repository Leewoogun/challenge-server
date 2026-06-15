package com.lwg.challenge.infra.jpa.userrecord

import com.lwg.challenge.infra.entity.userrecord.UserRecordEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * user_stats 테이블 CRUD. PK 가 user_id 이므로 JpaRepository<_, Long> 만으로 충분.
 */
interface UserRecordJpaRepository : JpaRepository<UserRecordEntity, Long>
