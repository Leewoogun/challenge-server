package com.lwg.challenge.infra.external.firebase

import com.lwg.challenge.domain.notification.NotificationType
import com.lwg.challenge.domain.notification.PushMessage
import com.lwg.challenge.domain.notification.SendResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * [NotificationSenderConfig] 의 발송기 선택 (push-fcm T-B4, 계약 §0.4).
 *
 * 🔴 **키가 없어도 빈 생성이 성공해야 한다.** 여기서 예외가 나면 컨텍스트 로드가 깨져
 * **서버가 아예 안 뜬다** — 알림 설정 오류의 대가로는 과하다.
 *
 * > 참고: "서버가 실제로 뜬다" 는 `ChallengeServerApplicationTests.contextLoads()` 가
 * > 이미 덮고 있다. 그 테스트는 전체 컨텍스트를 올리고 그 안에 이 `@Configuration` 이 포함되며,
 * > `challenge.fcm.credentials-path` 가 비어 있는 상태로 통과한다. 이 파일은 **선택 결과가
 * > 무엇인지**를 직접 확인한다.
 */
class NotificationSenderConfigTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `키 경로가 비어 있으면 NoOp 발송기를 고른다`() {
        val sender = assertDoesNotThrow { NotificationSenderConfig("").notificationSender() }

        assertInstanceOf(NoOpNotificationSender::class.java, sender)
    }

    /** 🔴 계약 §0.4: "키 설정 오류(잘못된 경로·손상된 JSON)가 서버를 죽이지 않는다." */
    @Test
    fun `경로가 잘못돼도 빈 생성이 실패하지 않고 NoOp 로 떨어진다`() {
        val missing = tempDir.resolve("없는키.json").toString()

        val sender = assertDoesNotThrow { NotificationSenderConfig(missing).notificationSender() }

        assertInstanceOf(NoOpNotificationSender::class.java, sender)
    }

    @Test
    fun `손상된 JSON 이어도 빈 생성이 실패하지 않고 NoOp 로 떨어진다`() {
        val broken = tempDir.resolve("broken.json")
        broken.writeText("{ nope ")

        val sender = assertDoesNotThrow { NotificationSenderConfig(broken.toString()).notificationSender() }

        assertInstanceOf(NoOpNotificationSender::class.java, sender)
    }

    /**
     * NoOp 은 [SendResult.SKIPPED] 를 준다 — [SendResult.FAILED] 가 아니다.
     *
     * 둘을 섞으면 "키가 없어서 안 보냄"(정상)과 "보내려다 실패"(이상)를 구분할 수 없다.
     * 나중에 실패율 알람을 붙였을 때 키 없는 개발 환경이 상시 알람을 울리게 된다.
     */
    @Test
    fun `NoOp 발송은 SKIPPED 이고 예외를 던지지 않는다`() {
        val sender = NotificationSenderConfig("").notificationSender()

        val result = assertDoesNotThrow {
            sender.send(
                fcmToken = "어떤-토큰이든",
                message = PushMessage("제목", "본문", NotificationType.CHALLENGE_REQUEST, 7L),
            )
        }

        assertEquals(SendResult.SKIPPED, result)
    }
}
