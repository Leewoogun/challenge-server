package com.lwg.challenge.infra.external.firebase

import com.lwg.challenge.domain.notification.NotificationSender
import com.lwg.challenge.domain.notification.PushMessage
import com.lwg.challenge.domain.notification.SendResult
import org.slf4j.LoggerFactory

/**
 * 발송하지 않는 [NotificationSender]. 서비스 계정 키가 없거나 초기화에 실패하면 이쪽이 선택된다.
 *
 * ## 이 클래스가 백엔드 전체를 Firebase 로부터 분리한다
 *
 * 덕분에 **Firebase 프로젝트 생성을 기다리지 않고 T-B4~T-B6 전체를 완성·테스트할 수 있다**
 * (spec 의존 관계: *"백엔드는 T-U1~T-U3 없이 전부 완료 가능하다"*). 키가 없으면 발송만
 * 건너뛰고 `notifications` row 저장·이벤트·엔드포인트는 전부 정상 동작한다.
 *
 * 🔴 **[SendResult.SKIPPED] 를 반환하지 [SendResult.FAILED] 가 아니다.** 둘을 섞으면
 * "키가 없어서 안 보냄"(정상)과 "보내려다 실패"(이상)를 구분할 수 없어진다. 특히 FAILED 로
 * 두면 나중에 실패율 알람을 붙였을 때 키 없는 개발 환경이 상시 알람을 울린다.
 *
 * 로그는 DEBUG 다 — 키가 없는 지금은 **모든 발송이 이 경로**라 INFO 면 로그가 도배된다.
 */
class NoOpNotificationSender : NotificationSender {

    private val log = LoggerFactory.getLogger(NoOpNotificationSender::class.java)

    override fun send(fcmToken: String, message: PushMessage): SendResult {
        log.debug(
            "푸시 발송 건너뜀 (발송기 비활성): type={}, referenceId={}",
            message.type,
            message.referenceId,
        )
        return SendResult.SKIPPED
    }
}
