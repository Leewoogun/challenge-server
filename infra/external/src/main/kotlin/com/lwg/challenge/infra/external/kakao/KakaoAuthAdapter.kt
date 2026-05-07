package com.lwg.challenge.infra.external.kakao

import com.lwg.challenge.domain.auth.KakaoAuthPort
import com.lwg.challenge.domain.auth.KakaoUserInfo
import org.springframework.stereotype.Component

/**
 * `KakaoAuthPort` 의 구현체. `KakaoOAuthClient` 의 raw 응답을 [KakaoUserInfo] 로 매핑.
 *
 * 던지는 예외(`KakaoTokenInvalidException`, `KakaoServerException`)는 클라이언트에서 그대로 전파된다.
 */
@Component
class KakaoAuthAdapter(
    private val client: KakaoOAuthClient,
) : KakaoAuthPort {

    override fun getUserInfo(accessToken: String): KakaoUserInfo {
        val raw = client.getUserInfo(accessToken)
        val account = raw.kakaoAccount
        return KakaoUserInfo(
            kakaoId = raw.id,
            nickname = account?.profile?.nickname,
            profileImageUrl = account?.profile?.profileImageUrl,
            rawPhoneNumber = if (account?.phoneNumberNeedsAgreement == true) null else account?.phoneNumber,
        )
    }
}
