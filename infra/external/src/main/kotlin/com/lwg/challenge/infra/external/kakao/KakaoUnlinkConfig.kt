package com.lwg.challenge.infra.external.kakao

import com.lwg.challenge.domain.auth.KakaoUnlinkPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * [KakaoUnlinkPort] 빈 선택 (mypage T-B2).
 *
 * ```
 * admin key 있음 → KakaoUnlinkAdapter      (실제 unlink)
 * admin key 없음 → NoOpKakaoUnlinkAdapter  (건너뜀 + WARN)
 * ```
 *
 * ## 왜 `@ConditionalOnProperty` 가 아니라 런타임 분기인가
 *
 * 🔴 **`NotificationSenderConfig` 의 패턴을 그대로 따른다** (상세 근거는 그 파일 KDoc).
 * 요약하면 `@ConditionalOnBean` 은 평가 순서에 의존해 **조용히 no-op 이 선택되는** 사고를
 * 낼 수 있고, `@ConditionalOnProperty` 는 *"속성은 있는데 값이 빈 문자열"* 을 구분하지 못한다.
 * 빈 하나가 런타임에 스스로 판단하면 두 문제가 다 사라지고, 분기가 한 함수에 모여 있어
 * 읽는 사람이 순서를 추론할 필요가 없다.
 *
 * ## 🔴 왜 fail-safe 인가 — `PhotoStorageConfig` 와 반대다
 *
 * admin key 는 **카카오 콘솔에서 사람이 발급해야 하는 비밀값**이라 로컬 개발 환경에는
 * 없는 것이 정상이다. 여기서 기동을 막으면 모바일이 **탈퇴 플로우를 로컬에서 전혀 테스트할 수
 * 없다.** 사진 저장 루트(`PhotoStorageConfig`)가 fail-fast 인 것과 갈리는 지점이 정확히
 * 여기다 — 저장 루트는 로컬에서도 만들 수 있지만 admin key 는 만들 수 없다.
 *
 * 대신 **운영 누락이 조용해지지 않도록** 경고를 두 겹으로 박는다:
 * 기동 시 1회(여기) + 실제로 건너뛸 때마다 1회([NoOpKakaoUnlinkAdapter]).
 * 기동 로그 한 줄은 스크롤 위로 사라지지만 호출 시 로그는 사고 시점에 남는다.
 */
@Configuration
class KakaoUnlinkConfig(
    /**
     * 서비스 앱 어드민 키. 기본값은 빈 문자열(미설정).
     *
     * ⚠️ 타입이 `String` 인 것이 의도다 — `NotificationSenderConfig.credentialsPath` 와 같은
     * 이유로, 변환 대상 타입으로 받으면 오타에 `ConversionFailedException` 이 나서 **서버가
     * 기동 중 죽는다.** 문자열로 받아 검증은 우리가 한다.
     *
     * [KakaoOAuthClient] 도 같은 속성을 읽는다. 중복처럼 보이지만 역할이 다르다 —
     * **여기는 "쓸 것인가" 를 정하고, 그쪽은 "어떻게 쓸 것인가" 를 안다.** 그쪽에 판단까지
     * 몰면 no-op 구현이 존재할 이유가 없어지고 건너뛰기가 로그 없이 조용해진다.
     */
    @Value("\${kakao.admin-key:}") private val adminKey: String,
) {

    private val log = LoggerFactory.getLogger(KakaoUnlinkConfig::class.java)

    @Bean
    fun kakaoUnlinkPort(client: KakaoOAuthClient): KakaoUnlinkPort {
        if (adminKey.isBlank()) {
            log.warn(
                "KAKAO_ADMIN_KEY 미설정 — 회원탈퇴 시 카카오 연결 해제를 건너뜁니다. " +
                    "운영 배포 전 반드시 설정하십시오. (DB 익명화와 사진 삭제는 정상 동작합니다)",
            )
            return NoOpKakaoUnlinkAdapter()
        }
        log.info("카카오 연결 해제 활성 — KakaoUnlinkAdapter 를 사용합니다.")
        return KakaoUnlinkAdapter(client)
    }
}
