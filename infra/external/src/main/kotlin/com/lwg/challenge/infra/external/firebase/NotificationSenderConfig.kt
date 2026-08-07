package com.lwg.challenge.infra.external.firebase

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.lwg.challenge.domain.notification.NotificationSender
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * [NotificationSender] 빈 선택 (push-fcm T-B4, 계약 §0.4).
 *
 * ```
 * 키 로드 성공 → FcmNotificationSender  (실제 발송)
 * 그 외 전부   → NoOpNotificationSender (발송만 건너뜀)
 * ```
 *
 * ## 왜 `@ConditionalOnBean`/`@ConditionalOnProperty` 가 아니라 런타임 분기인가
 *
 * `@ConditionalOnBean` 은 **평가 순서에 의존**한다 — 공식 문서도 auto-configuration 밖에서는
 * 신뢰하지 말라고 못박는다. 사용자 정의 `@Configuration` 에서 쓰면 등록 순서가 조금만 바뀌어도
 * **조용히 no-op 이 선택**될 수 있고, 그 사고는 "알림이 안 온다" 로만 드러나 원인 추적이 어렵다.
 *
 * `@ConditionalOnProperty` 로 갈랐다면 *"경로는 설정했는데 파일이 손상됐다"* 를 구분하지 못한다 —
 * 속성은 존재하므로 실제 발송기가 선택되고, 초기화 실패는 그때 터진다.
 *
 * **빈 하나가 런타임에 스스로 판단하면 두 문제가 다 사라진다.** 분기가 한 함수에 모여 있어
 * 읽는 사람이 순서를 추론할 필요도 없다. (`dev-test-login` 은 보안 경계라 fail-closed 커스텀
 * `Condition` 이 맞았지만, 여기는 **기능 저하**라 판단 지점이 다르다.)
 *
 * ## 🔴 이 메서드는 예외를 던지지 않는다
 *
 * 던지면 **빈 생성 실패 → 컨텍스트 로드 실패 → 서버가 안 뜬다.** 알림 설정 오류가 서비스 전체를
 * 내리는 것은 실패 모드로 과하다. 모든 위험 구간이 [FirebaseCredentialsLoader] 와
 * `runCatching` 안에 있다.
 */
@Configuration
class NotificationSenderConfig(
    /**
     * 서비스 계정 JSON 의 **절대 경로**. 기본값은 빈 문자열(미설정).
     *
     * ⚠️ 타입이 `String` 인 것이 의도다. `Boolean`/`Path` 같은 변환 대상 타입으로 받으면
     * 오타에 `ConversionFailedException` 이 나서 **서버가 기동 중 죽는다** (dev-test-login 실측).
     * 문자열로 받아 검증은 우리가 한다.
     *
     * 🔴 이 파일은 **레포 밖**에 둔다. `.gitignore` 에 `*-adminsdk-*.json` 등이 등록돼 있지만
     * 그건 실수 방어이지 보관 위치가 아니다.
     */
    @Value("\${challenge.fcm.credentials-path:}") private val credentialsPath: String,
) {

    private val log = LoggerFactory.getLogger(NotificationSenderConfig::class.java)

    @Bean
    fun notificationSender(): NotificationSender {
        val messaging = initializeMessagingOrNull()
        return if (messaging == null) {
            log.info("푸시 발송 비활성 — NoOpNotificationSender 를 사용합니다. (알림 row 저장은 정상 동작)")
            NoOpNotificationSender()
        } else {
            log.info("푸시 발송 활성 — FcmNotificationSender 를 사용합니다.")
            FcmNotificationSender(messaging)
        }
    }

    /**
     * FirebaseApp 을 초기화하고 [FirebaseMessaging] 을 얻는다. 실패하면 null.
     *
     * ⚠️ **이미 초기화된 경우를 반드시 다뤄야 한다.** `FirebaseApp.initializeApp()` 은 같은 이름의
     * 앱이 이미 있으면 `IllegalStateException` 을 던진다. 테스트 컨텍스트가 여러 번 뜨는 상황에서
     * 실제로 발생하므로, 기존 인스턴스를 재사용한다.
     */
    private fun initializeMessagingOrNull(): FirebaseMessaging? {
        val credentials = FirebaseCredentialsLoader.load(credentialsPath) ?: return null

        return runCatching {
            val app = FirebaseApp.getApps()
                .firstOrNull { it.name == APP_NAME }
                ?: FirebaseApp.initializeApp(
                    FirebaseOptions.builder().setCredentials(credentials).build(),
                    APP_NAME,
                )
            FirebaseMessaging.getInstance(app)
        }.onFailure { e ->
            log.warn("Firebase 초기화에 실패했습니다 ({}). 푸시 발송을 건너뜁니다.", e.message)
        }.getOrNull()
    }

    companion object {
        /**
         * 기본 앱(`[DEFAULT]`) 대신 이름 있는 앱을 쓴다 — 다른 Firebase 기능이 나중에 들어와도
         * 서로의 초기화를 밟지 않는다.
         */
        private const val APP_NAME = "challenge-fcm"
    }
}
