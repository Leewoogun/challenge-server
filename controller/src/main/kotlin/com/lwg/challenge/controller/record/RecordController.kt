package com.lwg.challenge.controller.record

import com.lwg.challenge.controller.record.dto.RecordData
import com.lwg.challenge.controller.record.dto.RecordResponse
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.domain.userrecord.UserRecord
import com.lwg.challenge.service.record.RecordService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 전적(record) 조회 컨트롤러.
 *
 * 인증은 SecurityFilterChain `.anyRequest().authenticated()` + `JwtAuthenticationFilter` 가 담당.
 * 정상 케이스에서는 SecurityContext 의 principal 이 userId(Long).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Record", description = "사용자 누적 전적 조회")
class RecordController(
    private val recordService: RecordService,
) {

    @GetMapping("/record")
    @Operation(
        summary = "내 전적 조회",
        description = "사용자 누적 전적(win/lose/draw/currentStreak). 신규 사용자는 0 으로 채워 응답.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun getMyRecord(): RecordResponse {
        val userId: Long = currentUserId()
        val record: UserRecord = recordService.getMyRecord(userId)
        return RecordResponse(data = record.toDto())
    }

    private fun currentUserId(): Long {
        val auth = SecurityContextHolder.getContext().authentication
            ?: throw UnauthorizedException("인증이 필요합니다")
        val principal = auth.principal
        return when (principal) {
            is Long -> principal
            is Number -> principal.toLong()
            is String -> principal.toLongOrNull()
                ?: throw UnauthorizedException("인증 정보가 올바르지 않습니다")
            else -> throw UnauthorizedException("인증 정보가 올바르지 않습니다")
        }
    }

    private fun UserRecord.toDto(): RecordData = RecordData(
        win = wins,
        lose = losses,
        draw = draws,
        currentStreak = currentStreak,
    )
}
