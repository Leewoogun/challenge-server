package com.lwg.challenge.service.user

import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.domain.user.User
import com.lwg.challenge.domain.user.UserRepository
import com.lwg.challenge.domain.user.UserStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 사용자 본인 정보 조회 (spec-user-info §4.3) + FCM 토큰 등록 (push-fcm T-B1).
 *
 * 토큰의 userId 로 단일 row 를 다룬다. 마이그레이션 0건 (V1 의 `users.fcm_token TEXT` 그대로).
 */
@Service
class UserService(
    private val userRepository: UserRepository,
) {

    private val log = LoggerFactory.getLogger(UserService::class.java)

    /**
     * 본인 정보 조회. 토큰의 userId 가 DB 에 없거나 **탈퇴한 계정**이면 401.
     *
     * 🔴 **`DELETED` 를 부재와 똑같이 취급하는 것이 이 메서드의 계약이다.** 원래 이 KDoc 은
     * *"DB 에 없으면(회원탈퇴/삭제) 401"* 이라고 적혀 있었다 — 탈퇴가 row 를 지운다는 전제였다.
     * mypage T-B2 가 그 전제를 바꿔 **탈퇴해도 row 가 남으므로**(상대의 챌린지가 FK 로 가리킨다)
     * status 검사를 더해야 예전과 같은 답이 나온다. 없으면 탈퇴자가 `nickname="탈퇴한 사용자"`,
     * `kakaoId=null` 인 자기 정보를 200 으로 돌려받는다.
     *
     * ⚠️ 이 검사는 **이 엔드포인트에만** 있다. `JwtAuthenticationFilter` 에 status 검사를 넣는
     * 것은 전 엔드포인트에 영향을 주는 별도 협의 사항이라 범위 밖이다 — 탈퇴 직후 최대 1시간
     * (access token 수명) 동안 다른 엔드포인트는 여전히 열려 있다.
     */
    @Transactional(readOnly = true)
    fun getMe(me: Long): User {
        val user = userRepository.findById(me) ?: throw UnauthorizedException("user not found")
        if (user.status == UserStatus.DELETED) throw UnauthorizedException("user withdrawn")
        return user
    }

    /**
     * FCM 토큰 등록·갱신 (push-fcm 계약 §1).
     *
     * 🔴 **반드시 두 단계다.**
     * ```
     * 1) UPDATE users SET fcm_token = NULL WHERE fcm_token = :token AND id <> :me
     * 2) UPDATE users SET fcm_token = :token WHERE id = :me
     * ```
     * 1단계가 없으면 **한 기기가 두 계정의 알림을 받는다.** 이건 이론적 위험이 아니라
     * `dev-test-login` 으로 테스트 계정을 한 기기에서 오가는 이 프로젝트의 표준 검증 방식에서
     * 곧바로 재현된다 (계약 §0.2).
     *
     * ⚠️ **`AND id <> :me` 가 이 메서드에서 가장 삭제되기 쉬운 조건이다.** 없어도 "다른 계정 토큰이
     * 밀린다"는 결과는 같아 보여서, 두 UPDATE 를 한 트랜잭션에서 보면 무해해 보인다. 그러나 그 조건이
     * 빠지면 1단계가 **자기 토큰까지 지우고**, 순서에 따라 재등록이 통째로 날아간다. 조건이 있는 덕분에
     * 두 단계는 순서와 무관하게 같은 결과를 내고 **멱등**해진다 — 모바일이 로그인 직후와 `onNewToken`
     * 양쪽에서 부르므로 중복 호출이 비정상이 아니라 **정상 경로**다.
     *
     * @throws UnauthorizedException 토큰의 userId 가 DB 에 없을 때 — [getMe] 와 같은 처리다.
     */
    @Transactional
    fun updateFcmToken(me: Long, fcmToken: String) {
        val evicted = userRepository.clearFcmTokenOwnedByOthers(fcmToken, me)
        if (evicted > 0) {
            // 계정 전환이 실제로 일어났다는 신호. 정상이지만 흔하지 않으므로 남긴다.
            log.info("FCM 토큰 소유권 이전: userId={}, 이전 소유자 {}건 해제", me, evicted)
        }

        val updated = userRepository.updateFcmToken(me, fcmToken)
        if (updated == 0) throw UnauthorizedException("user not found")
    }
}
