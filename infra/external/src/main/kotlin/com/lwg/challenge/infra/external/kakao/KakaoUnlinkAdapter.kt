package com.lwg.challenge.infra.external.kakao

import com.lwg.challenge.domain.auth.KakaoUnlinkPort
import org.slf4j.LoggerFactory

/**
 * [KakaoUnlinkPort] 의 실제 구현체. [KakaoOAuthClient.unlink] 에 위임한다 (mypage T-B2).
 *
 * 던지는 예외(`KakaoTokenInvalidException`, `KakaoServerException`)는 클라이언트에서 그대로
 * 전파된다 — `KakaoAuthAdapter` 와 같다. `-101`(이미 연결 없음)을 성공으로 삼키는 판단도
 * 클라이언트 안에 있어서, 이 클래스에는 분기가 없다.
 *
 * ⚠️ **`@Component` 가 없는 것이 의도다.** admin key 유무에 따라 이 구현과
 * [NoOpKakaoUnlinkAdapter] 중 하나가 선택되어야 하므로 배선은 [KakaoUnlinkConfig] 가 한다.
 * 여기에 `@Component` 를 달면 두 빈이 동시에 등록되어 주입이 모호해진다.
 */
class KakaoUnlinkAdapter(
    private val client: KakaoOAuthClient,
) : KakaoUnlinkPort {

    private val log = LoggerFactory.getLogger(KakaoUnlinkAdapter::class.java)

    override fun unlink(kakaoId: Long) {
        client.unlink(kakaoId)
        log.info("카카오 연결 해제 완료: kakaoId={}", kakaoId)
    }
}

/**
 * admin key 가 없는 환경에서 선택되는 **건너뛰기** 구현 (mypage T-B2).
 *
 * `NoOpNotificationSender` 와 같은 자리의 물건이다 — 기능만 빠지고 나머지 흐름은 그대로 돈다.
 * 탈퇴의 DB 익명화·사진 삭제는 전부 정상 수행되고, **카카오 연결만 살아남는다.**
 *
 * 🔴 **호출될 때마다 WARN 을 남긴다.** 기동 시 1회 경고만으로는 부족하다 — 로그가 길어지면
 * 그 한 줄은 스크롤 위로 사라지고, *"탈퇴했는데 카카오 연결이 남아 있다"* 는 사고는 훨씬
 * 나중에 발견된다. 실제로 그 일이 일어난 시점에 흔적이 남아야 추적이 가능하다.
 */
class NoOpKakaoUnlinkAdapter : KakaoUnlinkPort {

    private val log = LoggerFactory.getLogger(NoOpKakaoUnlinkAdapter::class.java)

    override fun unlink(kakaoId: Long) {
        log.warn(
            "KAKAO_ADMIN_KEY 미설정 — 회원탈퇴 시 카카오 연결 해제를 건너뜁니다. " +
                "운영 배포 전 반드시 설정하십시오. (건너뛴 kakaoId={})",
            kakaoId,
        )
    }
}
