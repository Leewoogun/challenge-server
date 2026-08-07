package com.lwg.challenge.service.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * [NotificationDispatcher.onChallengeNotification] 의 **어노테이션 배선**을 고정한다
 * (push-fcm T-B5, 계약 §0.3).
 *
 * ## 🔴 왜 어노테이션을 테스트하는가 — 빠뜨려도 아무것도 실패하지 않기 때문이다
 *
 * ```
 * @TransactionalEventListener(AFTER_COMMIT) 없음
 *   → 커밋 전에 실행된다. 롤백돼도 알림이 나간다 = 일어나지 않은 일을 알린다.
 *
 * @Transactional(REQUIRES_NEW) 없음
 *   → AFTER_COMMIT 시점엔 참여할 트랜잭션이 없다.
 *     save() 가 **예외 없이 조용히 사라진다.**
 * ```
 *
 * 둘 다 **컴파일도 되고 단위 테스트도 초록**이다. 계약 §0.3 이 *"이 프로젝트가 반복해서 겪은
 * '테스트는 초록인데 실제로는 안 됨' 계열"* 이라고 못박은 이유가 이것이다.
 *
 * ## ⚠️ 이 테스트가 증명하지 **못하는** 것
 *
 * **어노테이션이 붙어 있다는 사실만 본다. 실제 트랜잭션 동작은 보지 않는다.**
 * "정말로 커밋 후에 실행되는가", "정말로 새 트랜잭션에서 커밋되는가" 는 Spring 이 보장하는
 * 영역이고, 확인하려면 실제 트랜잭션 매니저와 DB 가 필요하다 —
 * **Testcontainers 통합 테스트(현재 컨테이너 런타임 부재로 skip)에서만 가능하다.**
 *
 * 구조적 단언이라 약하지만, **막으려는 실패 모드가 "누가 어노테이션을 지운다" 라서 정확히 그
 * 지점을 겨눈다.** 없는 것보다 확실히 낫고, 있는 것 이상으로 읽히면 안 된다.
 */
class NotificationDispatcherWiringTest {

    private val method = NotificationDispatcher::class.java
        .declaredMethods
        .single { it.name == "onChallengeNotification" }

    @Test
    fun `AFTER_COMMIT 트랜잭션 이벤트 리스너다`() {
        val annotation = method.getAnnotation(TransactionalEventListener::class.java)

        assertNotNull(
            annotation,
            "@TransactionalEventListener 가 사라지면 커밋 여부와 무관하게 알림이 나간다",
        )
        assertEquals(
            TransactionPhase.AFTER_COMMIT,
            annotation.phase,
            "AFTER_COMMIT 이 아니면 롤백된 요청에도 알림이 발송된다",
        )
    }

    @Test
    fun `REQUIRES_NEW 트랜잭션에서 실행된다`() {
        val annotation = method.getAnnotation(Transactional::class.java)

        assertNotNull(
            annotation,
            "@Transactional 이 없으면 AFTER_COMMIT 시점에 참여할 트랜잭션이 없어 저장이 조용히 사라진다",
        )
        assertEquals(
            Propagation.REQUIRES_NEW,
            annotation.propagation,
            "REQUIRES_NEW 가 아니면 이미 끝난 트랜잭션에 참여하려다 저장이 유실된다",
        )
    }
}
