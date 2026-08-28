package com.lwg.challenge.domain.auth

/**
 * 카카오 **연결 끊기(unlink)** 포트 (mypage T-B2).
 *
 * 구현체는 `:infra:external` 의 `KakaoUnlinkAdapter` 이며, admin key 가 없는 환경에서는
 * `NoOpKakaoUnlinkAdapter` 가 선택된다 (`KakaoUnlinkConfig` 가 런타임에 배선).
 * `NotificationSender` ↔ `FcmNotificationSender`/`NoOpNotificationSender` 와 같은 구조다.
 *
 * ## 🔴 왜 access token 이 아니라 kakaoId 를 받는가
 *
 * **서버는 카카오 access token 을 저장하지 않는다.** 로그인 때 `/v2/user/me` 한 번 쓰고 버린다
 * ([KakaoAuthPort]). 토큰 방식이면 앱이 탈퇴 시점에 카카오 토큰을 새로 받아 보내야 하고,
 * 기기의 카카오 세션이 만료돼 있으면 **탈퇴 자체가 불가능**해진다. admin key 방식은
 * `users.kakao_id` 만으로 동작하므로 그 실패 모드가 없다.
 *
 * @throws KakaoTokenInvalidException admin key 가 유효하지 않음 (401/403) 또는 그 외 4xx
 * @throws KakaoServerException 5xx / 타임아웃 / 네트워크 오류 (구현체 내부에서 1회 재시도 후에도 실패)
 */
interface KakaoUnlinkPort {

    /**
     * [kakaoId] 계정과 우리 앱의 연결을 끊는다.
     *
     * 🔴 **이미 연결이 없으면(카카오 에러 코드 `-101`) 성공으로 간주**하고 조용히 반환한다.
     * 탈퇴는 재시도될 수 있는 연산이고(1차 시도에서 unlink 는 됐는데 DB 커밋이 실패한 경우가
     * 실제로 존재한다), *"이미 원하는 상태"* 를 실패로 답하면 그 사용자는 **영원히 탈퇴하지
     * 못한다.** 멱등성이 여기서는 편의가 아니라 정확성이다.
     */
    fun unlink(kakaoId: Long)
}
