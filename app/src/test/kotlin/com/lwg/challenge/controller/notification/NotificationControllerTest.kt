package com.lwg.challenge.controller.notification

import com.lwg.challenge.controller.common.exception.GlobalExceptionHandler
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.service.notification.NotificationCommandService
import com.lwg.challenge.service.notification.NotificationPage
import com.lwg.challenge.service.notification.NotificationQueryService
import com.lwg.challenge.service.notification.NotificationView
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/**
 * [NotificationController] 슬라이스 테스트 — 엔드포인트 3개
 * (`GET /notifications`, `GET /notifications/unread-count`, `POST /notifications/read-all`).
 *
 * - `@WebMvcTest` 로 컨트롤러 + GlobalExceptionHandler 만 로드 (DB 불필요).
 * - `addFilters=false` 로 SecurityFilterChain 우회. SecurityContext 에 직접 principal=Long 주입.
 * - 페이지 판정 규칙(`size + 1`, `nextNotificationId`, `isPageEnd` 산출)은 `NotificationQueryServiceTest`, 읽음 처리의
 *   *"0 은 실측이다"* 규칙은 `NotificationCommandServiceTest`, SQL 은 실구동이 본다.
 *   여기서 보는 것은 **wire 로 나가는 모양**과 **쿼리 파라미터 파싱**이다.
 *
 * ## 이 파일이 지키는 계약 6개
 *
 * 1. 🔴 **`type` 은 열린 집합**이다 — enum 에 없는 값도 그대로 직렬화된다. 이 단언이 `type` 을
 *    `NotificationType` 으로 되돌리는 것을 막는 **유일한 자동 장치**다.
 * 2. 🔴 **`isRead` / `title` 은 응답에 없다** (둘 다 의도적 부재). `isRead` 는 정책이
 *    *"목록을 열면 전부 읽음"* 이라 행별 값의 정보량이 0이기 때문이고, `title` 은 카드가
 *    `body` 단독 1행이라 화면이 그리지 않기 때문이다. ⚠️ 둘 다 **DB 컬럼은 그대로 있다** —
 *    없는 것은 wire 뿐이다.
 * 3. 🔴 **null 이어도 키는 남는다** (`nextNotificationId` / `referenceId` / `referencePending`) — 이 프로젝트
 *    Jackson 에 `@JsonInclude(NON_NULL)` 이 없다는 사실에 모바일이 기대고 있다. `referencePending` 은
 *    특히 그렇다 — `null` 이 **«이 필드를 보지 마라»** 라는 세 번째 상태라, 키가 사라지면 앱이 그
 *    상태를 표현할 방법 자체를 잃는다.
 * 4. 🔴 **파라미터 오류는 HTTP 500 이 아니라 200 + code=700** 이다.
 * 5. 🔴 **뱃지 축과 목록 축의 DTO 가 섞이지 않는다** — 읽음 엔드포인트 응답에 `notifications` 가
 *    없고, 목록 응답에 `unreadCount` 가 없다. 섞이면 앱이 뱃지 갱신 경로를 두 벌 갖는다.
 * 6. 🔴 **`read-all` 이 돌려주는 개수는 컨트롤러가 만든 상수가 아니다** — 서비스가 준 값을
 *    그대로 싣는다 (0 이 아닌 값을 주면 0 이 아닌 값이 나간다).
 */
@WebMvcTest(controllers = [NotificationController::class])
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class NotificationControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var notificationQueryService: NotificationQueryService

    @MockitoBean
    lateinit var notificationCommandService: NotificationCommandService

    private val meId = 1L

    @BeforeEach
    fun setUpAuthentication() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(meId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
    }

    private fun view(
        notificationId: Long = 57L,
        type: String = "CHALLENGE_ACCEPTED",
        body: String = "민수님이 수락했습니다",
        referenceId: Long? = 1001L,
        // 기본 타입이 `CHALLENGE_ACCEPTED` 라 기본값도 null 이다 — 이 타입엔 개념이 없다.
        referencePending: Boolean? = null,
        createdAt: LocalDateTime = LocalDateTime.of(2026, 9, 1, 13, 42, 7),
    ) = NotificationView(
        notificationId = notificationId,
        type = type,
        body = body,
        referenceId = referenceId,
        referencePending = referencePending,
        createdAt = createdAt,
    )

    /** 파라미터 없이 부르면 서비스에 `(meId, null, 20)` 이 간다 — 그 스텁을 세운다. */
    private fun stubDefault(page: NotificationPage) {
        Mockito.`when`(notificationQueryService.getNotifications(meId, null, DEFAULT_SIZE)).thenReturn(page)
    }

    // ─── 200 shape ───────────────────────────────────────────────

    @Test
    fun `getNotifications - 정상 응답 + 전 필드가 그대로 실린다`() {
        stubDefault(
            NotificationPage(
                notifications = listOf(
                    view(),
                    view(
                        notificationId = 40L,
                        type = "CHALLENGE_REJECTED",
                        body = "민수님이 도망쳤습니다",
                        referenceId = 1000L,
                        createdAt = LocalDateTime.of(2026, 8, 31, 9, 5, 0),
                    ),
                ),
                nextNotificationId = 38L,
            ),
        )

        mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.notifications.length()").value(2))
            .andExpect(jsonPath("$.data.notifications[0].notificationId").value(57))
            .andExpect(jsonPath("$.data.notifications[0].type").value("CHALLENGE_ACCEPTED"))
            .andExpect(jsonPath("$.data.notifications[0].body").value("민수님이 수락했습니다"))
            .andExpect(jsonPath("$.data.notifications[0].referenceId").value(1001))
            .andExpect(jsonPath("$.data.notifications[0].createdAt").value("2026-09-01 13:42:07"))
            .andExpect(jsonPath("$.data.notifications[1].notificationId").value(40))
            .andExpect(jsonPath("$.data.notifications[1].type").value("CHALLENGE_REJECTED"))
            .andExpect(jsonPath("$.data.notifications[1].createdAt").value("2026-08-31 09:05:00"))
            .andExpect(jsonPath("$.data.nextNotificationId").value(38))
    }

    /**
     * 🔴 **ADR-0010 — `yyyy-MM-dd HH:mm:ss`. `T` 도 `Z` 도 없다.**
     *
     * `@JsonFormat` 을 빠뜨리면 `"2026-09-01T13:42:07"` 로 나가고, 모바일의 KST 파서가 그 자리에서
     * 깨진다. jsonPath 값 비교만으로는 구분자를 못 잡는 경우가 있어 **원문**까지 본다.
     */
    @Test
    fun `getNotifications - createdAt 이 T 나 Z 없이 KST 표기로 나간다`() {
        stubDefault(NotificationPage(notifications = listOf(view()), nextNotificationId = null))

        val body = mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.notifications[0].createdAt").value("2026-09-01 13:42:07"))
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(
            body.contains(""""createdAt":"2026-09-01 13:42:07""""),
            "ADR-0010 표기를 벗어났다: $body",
        )
        assertTrue(!body.contains("2026-09-01T"), "ISO 구분자 T 가 섞이면 모바일 파서가 깨진다: $body")
        assertTrue(!body.contains("13:42:07Z"), "UTC 표기 Z 가 붙으면 안 된다: $body")
    }

    /**
     * 🔴 **`nextNotificationId` 가 null 이어도 키는 실려 나간다** — `doesNotExist()` 가 아니다.
     *
     * 키 부재와 값 null 은 모바일에서 서로 다른 예외라(`MissingFieldException` vs
     * `JsonConvertException`) 앱이 *"키는 항상 있고 null 이면 끝"* 을 전제한다. 누가
     * `@JsonInclude(NON_NULL)` 을 켜면 **목록의 마지막 페이지에서만** 키가 사라져 실패 모드가
     * 조용히 바뀐다 — 첫 페이지 테스트로는 절대 안 잡힌다.
     */
    @Test
    fun `getNotifications - nextNotificationId 가 null 이어도 키가 존재한다`() {
        stubDefault(NotificationPage(notifications = listOf(view()), nextNotificationId = null))

        val body = mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nextNotificationId").value(nullValue()))
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(
            body.contains(""""nextNotificationId":null"""),
            "null 이어도 키가 사라지면 안 된다 (앱이 키 존재를 전제한다): $body",
        )
    }

    // ─── 🔴 끝 신호 불변식 — isPageEnd == (nextNotificationId == null) (ADR-0014 §2) ──

    /**
     * 🔴 **중간 페이지 — `isPageEnd: false` + `nextNotificationId` 값.**
     *
     * 끝 신호가 둘이라(ADR-0014) 둘 중 하나만 옮겨도, 하나를 상수로 박아도 응답 shape 은 멀쩡하다.
     * 그래서 끝/중간/0건 **세 경우를 다** wire 원문으로 본다 — 한 경우만 보면 상수 하나가 통과한다.
     */
    @Test
    fun `getNotifications - 중간 페이지는 isPageEnd false 이고 nextNotificationId 가 실린다`() {
        // 이번 페이지 마지막이 38 이고, 다음 페이지 첫 항목(초과분)이 37 이다 (ADR-0014 §3).
        stubDefault(NotificationPage(notifications = listOf(view(notificationId = 38L)), nextNotificationId = 37L))

        val body = mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nextNotificationId").value(37))
            .andExpect(jsonPath("$.data.isPageEnd").value(false))
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(body.contains(""""isPageEnd":false"""), "키 이름이 isPageEnd 로 나가야 한다: $body")
    }

    /** 🔴 **끝 페이지 — `isPageEnd: true` + `nextNotificationId: null`.** 둘 다 키가 실린다. */
    @Test
    fun `getNotifications - 끝 페이지는 isPageEnd true 이고 nextNotificationId 가 null 이다`() {
        stubDefault(NotificationPage(notifications = listOf(view()), nextNotificationId = null))

        val body = mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nextNotificationId").value(nullValue()))
            .andExpect(jsonPath("$.data.isPageEnd").value(true))
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(body.contains(""""isPageEnd":true"""), "키 이름이 isPageEnd 로 나가야 한다: $body")
        assertTrue(body.contains(""""nextNotificationId":null"""), "null 이어도 키가 실려야 한다: $body")
    }

    /**
     * 🔴 **v2 키(`nextCursor`)는 응답에 없다** — ADR-0014 breaking change.
     *
     * 남아 있으면 앱이 옛 키를 계속 읽어도 동작해 **이전이 끝났는지 아무도 모른다.** 두 이름이
     * 공존하면 끝 신호가 셋이 된다.
     */
    @Test
    fun `getNotifications - 폐기된 nextCursor 키는 응답에 없다`() {
        stubDefault(NotificationPage(notifications = listOf(view()), nextNotificationId = 38L))

        val body = mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(!body.contains("nextCursor"), "폐기된 키가 남아 있다: $body")
        // Kotlin `isXxx` 프로퍼티는 Jackson 이 `is` 접두를 떼어 `pageEnd` 로 낼 수 있는 자리다.
        assertTrue(!body.contains(""""pageEnd":"""), "is 접두가 떨어진 키가 나갔다: $body")
    }

    /**
     * 🔴 **`referencePending` 값이 그대로 실린다 — `true` 도 `false` 도.**
     *
     * 컨트롤러가 3값 중 하나라도 접으면(`?: false` 같은 것) 여기서 깨진다. 산출은 서비스 몫이고
     * (`NotificationQueryServiceTest`), 여기서 보는 것은 **wire 로 나가는 모양** 하나다.
     */
    @Test
    fun `getNotifications - referencePending 이 true 와 false 로 그대로 실린다`() {
        stubDefault(
            NotificationPage(
                notifications = listOf(
                    view(
                        notificationId = 57L,
                        type = "CHALLENGE_REQUEST",
                        body = "민수님이 도전장을 보냈습니다",
                        referenceId = 1001L,
                        referencePending = true,
                    ),
                    view(
                        notificationId = 56L,
                        type = "CHALLENGE_REQUEST",
                        body = "영희님이 도전장을 보냈습니다",
                        referenceId = 1002L,
                        referencePending = false,
                    ),
                ),
                nextNotificationId = null,
            ),
        )

        val body = mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.notifications[0].referencePending").value(true))
            .andExpect(jsonPath("$.data.notifications[1].referencePending").value(false))
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(body.contains(""""referencePending":true"""), "true 가 그대로 나가야 한다: $body")
        assertTrue(body.contains(""""referencePending":false"""), "false 가 그대로 나가야 한다: $body")
    }

    /**
     * 🔴 **`referencePending` 이 null 이어도 키는 실려 나간다** — `doesNotExist()` 가 아니다.
     *
     * `nextNotificationId` / `referenceId` 와 같은 사정이다 (이 프로젝트 Jackson 에 `@JsonInclude(NON_NULL)`
     * 이 없다). 여기서는 이유가 하나 더 있다 — `null` 은 «비탭» 이 아니라 **«이 필드를 보지 마라»**
     * 라서, 키가 통째로 사라지면 앱이 그 세 번째 상태를 표현할 방법을 잃는다. 그러면 남는 선택지는
     * *"없으면 비탭"* 또는 *"없으면 탭 가능"* 뿐이고 **둘 다 틀린다** (`NotificationDto` KDoc).
     */
    @Test
    fun `getNotifications - referencePending 이 null 이어도 키가 존재한다`() {
        stubDefault(
            NotificationPage(
                notifications = listOf(view(type = "OPPONENT_VERIFIED", referencePending = null)),
                nextNotificationId = null,
            ),
        )

        val body = mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            // 🔴 doesNotExist() 가 아니다 — 키는 있고 값이 null 이다.
            .andExpect(jsonPath("$.data.notifications[0].referencePending").value(nullValue()))
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(
            body.contains(""""referencePending":null"""),
            "null 이어도 키가 사라지면 앱이 «이 필드를 보지 마라» 를 표현할 수 없다: $body",
        )
    }

    /** 🔴 `referenceId` 도 같다 — null 이어도 키가 남는다. */
    @Test
    fun `getNotifications - referenceId 가 null 이어도 키가 존재한다`() {
        stubDefault(NotificationPage(notifications = listOf(view(referenceId = null)), nextNotificationId = null))

        val body = mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.notifications[0].referenceId").value(nullValue()))
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(body.contains(""""referenceId":null"""), "null 이어도 키가 사라지면 안 된다: $body")
    }

    /**
     * 🔴 **`isRead` 는 응답에 없다 — 읽음 처리가 붙은 뒤에도 그렇다.**
     *
     * 정책이 **행 단위가 아니라 *"목록을 열면 전부 읽음"*** 이라 행별 값은 여전히 정보량이 0이고,
     * 안 읽음을 표현하는 축은 목록이 아니라 **홈 벨 뱃지**(`unread-count`) 하나다.
     *
     * 🔴 되살릴 유인은 오히려 **늘었다** — 도메인 모델(`Notification.isRead`)과 DB 컬럼에 있는
     * 데다 이제 읽음 처리 API 까지 생겼으니 *"이제 넣어도 되겠네"* 가 자연스러워 보인다. 반대다.
     * 행 단위 읽음을 **채택하지 않은 것**이 결정이고, 이 필드가 돌아오면 *"언제 읽음인가"* 라는
     * 제품 결정이 함께 돌아온다 (`NotificationDto` KDoc).
     */
    @Test
    fun `getNotifications - isRead 는 응답에 없다`() {
        stubDefault(NotificationPage(notifications = listOf(view()), nextNotificationId = null))

        val body = mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.notifications[0].isRead").doesNotExist())
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(!body.contains("isRead"), "읽음 상태가 wire 로 새면 안 된다: $body")
        assertTrue(!body.contains(""""read""""), "이름만 바꿔서 되살리는 것도 막는다: $body")
    }

    /**
     * 🔴 **`title` 은 응답에 없다 — 화면이 그리지 않기 때문이지, 서버가 안 갖고 있어서가 아니다.**
     *
     * ⚠️ 이 테스트가 깨졌을 때 *"서버가 title 을 저장하지 않는구나"* 로 읽지 마라. **틀렸다.**
     * `notifications.title` 컬럼은 그대로 있고 발송 시점 문구가 박제돼 남아 있으며, 푸시가 그
     * 값을 헤드라인으로 쓴다. 빠진 곳은 **목록 응답 하나**다.
     *
     * design §3.3 판정: 알림 카드는 **`body` 단독 1행**이다. 실측 `title` 4종
     * (`영혼의 맹세` / `계약 완료.` / `ㅠㅠ` / `증거 도착`)은 **푸시 헤드라인으로 설계된 문구라
     * 목록에서 홀로 서지 못한다** — 2행으로 만들면 카드 높이가 1.5배가 되는데 얻는 정보가 `ㅠㅠ` 다.
     * 응답 shape 은 앱이 실제로 그리는 것만 싣는다 (spec T-B1).
     *
     * 화면이 2행을 원하게 되면 그때 되살려라 — **필드 추가는 non-breaking** 이고 값은 이미 DB 에 있다.
     * 그 결정 없이 매핑을 되돌리는 것만 이 단언이 막는다 (`NotificationDto` KDoc).
     */
    @Test
    fun `getNotifications - title 은 응답에 없다 - 카드는 body 단독 1행이다`() {
        stubDefault(NotificationPage(notifications = listOf(view()), nextNotificationId = null))

        val body = mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.notifications[0].title").doesNotExist())
            // 카드가 그리는 문구는 body 하나다 — title 이 빠져도 이건 남는다.
            .andExpect(jsonPath("$.data.notifications[0].body").value("민수님이 수락했습니다"))
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(!body.contains("title"), "화면이 그리지 않는 문구가 wire 로 새면 안 된다: $body")
    }

    /**
     * 🔴 **`type` 은 열린 집합이다 — enum 에 없는 값도 그대로 문자열로 나간다.**
     *
     * 이 단언이 §열린 집합 결정의 **회귀 장치**다. `NotificationDto.type` 을 `NotificationType`
     * 으로 되돌리면 여기서 즉시 깨진다 (enum 은 `SOME_FUTURE_TYPE` 을 표현할 수 없다).
     *
     * 실제로 이런 값이 나갈 상황은 가정이 아니다 — `NotificationType` 은 8종이 선언돼 있고 그중
     * **4종만 발송 가능**하며, 나머지는 각 feature 가 붙을 때 열리기로 이미 정해져 있다.
     * 목록이라 **한 행만 새 타입이어도 배열 전체의 역직렬화가 깨져 알림 화면이 통째로 빈다.**
     */
    @Test
    fun `getNotifications - enum 에 없는 type 도 문자열로 그대로 직렬화된다`() {
        stubDefault(
            NotificationPage(
                notifications = listOf(view(notificationId = 99L, type = "SOME_FUTURE_TYPE")),
                nextNotificationId = null,
            ),
        )

        val body = mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.notifications[0].type").value("SOME_FUTURE_TYPE"))
            .andExpect(jsonPath("$.data.notifications[0].type").isString)
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(
            body.contains(""""type":"SOME_FUTURE_TYPE""""),
            "서버가 모르는 타입이 응답을 깨뜨리면 안 된다: $body",
        )
    }

    /** 알림이 0건이면 빈 배열이다 — 에러가 아니다. */
    @Test
    fun `getNotifications - 알림이 없으면 빈 배열로 정상 응답한다`() {
        stubDefault(NotificationPage(notifications = emptyList(), nextNotificationId = null))

        mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.data.notifications").isArray)
            .andExpect(jsonPath("$.data.notifications.length()").value(0))
            .andExpect(jsonPath("$.data.nextNotificationId").value(nullValue()))
            // 🔴 0건도 끝이다 — 불변식의 세 번째 경우 (ADR-0014 §2).
            .andExpect(jsonPath("$.data.isPageEnd").value(true))
    }

    // ─── 파라미터 파싱 ───────────────────────────────────────────

    /** 파라미터 없이 부르면 `nextNotificationId=null` + `size=20`(기본값)이 서비스로 간다. */
    @Test
    fun `getNotifications - 파라미터가 없으면 nextNotificationId null 과 기본 size 로 호출된다`() {
        stubDefault(NotificationPage(notifications = emptyList(), nextNotificationId = null))

        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isOk)

        Mockito.verify(notificationQueryService).getNotifications(meId, null, DEFAULT_SIZE)
    }

    @Test
    fun `getNotifications - nextNotificationId 와 size 가 파싱돼 서비스에 전달된다`() {
        Mockito.`when`(notificationQueryService.getNotifications(meId, 38L, 5))
            .thenReturn(NotificationPage(notifications = listOf(view()), nextNotificationId = null))

        mockMvc.perform(get("/api/v1/notifications").param("nextNotificationId", "38").param("size", "5"))
            .andExpect(status().isOk)

        Mockito.verify(notificationQueryService).getNotifications(meId, 38L, 5)
    }

    /** 빈 문자열(`?size=`)은 미지정과 같다 — 앱이 빈 값을 붙여 보내도 첫 진입이 깨지지 않는다. */
    @Test
    fun `getNotifications - size 가 빈 문자열이면 기본값 20 이다`() {
        stubDefault(NotificationPage(notifications = emptyList(), nextNotificationId = null))

        mockMvc.perform(get("/api/v1/notifications").param("size", "")).andExpect(status().isOk)

        Mockito.verify(notificationQueryService).getNotifications(meId, null, DEFAULT_SIZE)
    }

    /** `?nextNotificationId=` 도 마찬가지 — 첫 페이지다. */
    @Test
    fun `getNotifications - nextNotificationId 가 빈 문자열이면 첫 페이지다`() {
        stubDefault(NotificationPage(notifications = emptyList(), nextNotificationId = null))

        mockMvc.perform(get("/api/v1/notifications").param("nextNotificationId", "")).andExpect(status().isOk)

        Mockito.verify(notificationQueryService).getNotifications(meId, null, DEFAULT_SIZE)
    }

    // ─── 🔴 파라미터 오류 = HTTP 200 + code 700 ──────────────────

    /**
     * 🔴 **HTTP 500 이 아니라 200 + code=700 이다.**
     *
     * `@RequestParam size: Int?` 로 선언했다면 Spring 이 `MethodArgumentTypeMismatchException` 을
     * 던지고, `GlobalExceptionHandler` 에 그 핸들러가 없어 `handleUncaught` → **HTTP 500** 이
     * 나갔을 자리다. 클라이언트 실수 하나가 서버 장애로 보고되는 것을 막는 것이 문자열 파싱의
     * 이유다 (ADR-0002: 5xx 는 인프라 장애 전용). `ChallengeHistoryController` 의 `?month=` 가 같은
     * 선례다.
     */
    @Test
    fun `getNotifications - size 가 숫자가 아니면 HTTP 200 + code 700`() {
        mockMvc.perform(get("/api/v1/notifications").param("size", "abc"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))

        verifyServiceNeverCalled()
    }

    /**
     * 🔴 **범위 밖은 clamp 가 아니라 거부다.**
     *
     * `size=51` 을 조용히 50 으로 깎으면 앱은 51건을 받았다고 믿고, 자체 계산한 기준 id 로 다음
     * 페이지를 이어 붙이는 순간 **1건이 예외도 로그도 없이 사라진다.** 보관함이 미래 달을
     * clamp 하지 않는 것과 같은 판단이다.
     */
    @Test
    fun `getNotifications - size 가 범위 밖이면 clamp 하지 않고 700 으로 거부한다`() {
        mockMvc.perform(get("/api/v1/notifications").param("size", "0"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))

        mockMvc.perform(get("/api/v1/notifications").param("size", "51"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))

        mockMvc.perform(get("/api/v1/notifications").param("size", "-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))

        verifyServiceNeverCalled()
    }

    /** 경계 자체는 통과한다 — 한 칸 틀리면 정상 요청이 막힌다. */
    @Test
    fun `getNotifications - size 1 과 50 은 허용된다`() {
        val page = NotificationPage(notifications = emptyList(), nextNotificationId = null)
        Mockito.`when`(notificationQueryService.getNotifications(meId, null, 1)).thenReturn(page)
        Mockito.`when`(notificationQueryService.getNotifications(meId, null, 50)).thenReturn(page)

        mockMvc.perform(get("/api/v1/notifications").param("size", "1")).andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/notifications").param("size", "50")).andExpect(status().isOk)

        Mockito.verify(notificationQueryService).getNotifications(meId, null, 1)
        Mockito.verify(notificationQueryService).getNotifications(meId, null, 50)
    }

    @Test
    fun `getNotifications - nextNotificationId 가 숫자가 아니면 HTTP 200 + code 700`() {
        mockMvc.perform(get("/api/v1/notifications").param("nextNotificationId", "abc"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))

        verifyServiceNeverCalled()
    }

    // ─── 인증 ────────────────────────────────────────────────────

    /** SecurityContext 가 비면 컨트롤러의 `currentUserId()` 가 401 로 끊는다. */
    @Test
    fun `getNotifications - 인증 정보가 없으면 HTTP 401 + code 401`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))

        verifyServiceNeverCalled()
    }

    /**
     * 컨트롤러가 서비스에 넘기는 userId 가 SecurityContext principal 과 같아야 한다.
     * 여기가 어긋나면 **남의 수신함을 보게 된다** — 응답 형태는 멀쩡해서 jsonPath 로는 안 잡힌다.
     */
    @Test
    fun `getNotifications - service 에 SecurityContext principal 이 그대로 전달된다`() {
        stubDefault(NotificationPage(notifications = emptyList(), nextNotificationId = null))

        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isOk)

        Mockito.verify(notificationQueryService).getNotifications(meId, null, DEFAULT_SIZE)
    }

    // ─── unread-count (홈 벨 뱃지) ───────────────────────────────

    @Test
    fun `getUnreadCount - 정상 응답 + unreadCount 가 그대로 실린다`() {
        Mockito.`when`(notificationQueryService.getUnreadCount(meId)).thenReturn(3)

        mockMvc.perform(get("/api/v1/notifications/unread-count"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.unreadCount").value(3))

        Mockito.verify(notificationQueryService).getUnreadCount(meId)
    }

    /** 0 은 에러가 아니다 — *"뱃지를 꺼라"* 는 정상 응답이다. */
    @Test
    fun `getUnreadCount - 0 이어도 정상 응답이다`() {
        Mockito.`when`(notificationQueryService.getUnreadCount(meId)).thenReturn(0)

        mockMvc.perform(get("/api/v1/notifications/unread-count"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.unreadCount").value(0))
    }

    /**
     * 🔴 **리터럴 경로가 목록 매핑에 먹히지 않는다.**
     *
     * `/notifications/unread-count` 가 `/notifications` 로 잘못 라우팅되면 목록 서비스가 불리고
     * 응답에 `notifications` 배열이 실린다. 아래 `두 읽음 엔드포인트 응답에는 notifications 가 없다`
     * 가 그 shape 을 막고, 여기서는 **목록 서비스가 아예 안 불린다**는 것을 본다.
     */
    @Test
    fun `getUnreadCount - 목록 서비스는 호출되지 않는다`() {
        Mockito.`when`(notificationQueryService.getUnreadCount(meId)).thenReturn(3)

        mockMvc.perform(get("/api/v1/notifications/unread-count")).andExpect(status().isOk)

        verifyServiceNeverCalled()
    }

    // ─── read-all (목록을 열면 전부 읽음) ────────────────────────

    /**
     * 🔴 **응답은 `BaseResponse()` 가 아니라 개수다** — 앱이 *"read-all 을 불렀으니 뱃지는 0"*
     * 이라는 규칙을 갖지 않게 하기 위해서다. 앱은 홈 진입이든 read-all 직후든 **서버가 준
     * `unreadCount` 하나만** 보고 뱃지를 켠다 (`UnreadCountData` KDoc).
     */
    @Test
    fun `readAll - 정상 응답 + unreadCount 0`() {
        Mockito.`when`(notificationCommandService.markAllAsRead(meId)).thenReturn(0)

        mockMvc.perform(post("/api/v1/notifications/read-all"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(false))
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.unreadCount").value(0))

        Mockito.verify(notificationCommandService).markAllAsRead(meId)
    }

    /** 요청 body 없이 부른다 — 대상은 언제나 *"내 안 읽은 알림 전부"* 라 실을 것이 없다. */
    @Test
    fun `readAll - body 없이 호출된다`() {
        Mockito.`when`(notificationCommandService.markAllAsRead(meId)).thenReturn(0)

        mockMvc.perform(post("/api/v1/notifications/read-all")).andExpect(status().isOk)

        Mockito.verify(notificationCommandService).markAllAsRead(meId)
    }

    /** **멱등** — 이미 다 읽은 상태에서 다시 불러도 200 + `unreadCount: 0` 이다. */
    @Test
    fun `readAll - 이미 다 읽은 상태에서 다시 불러도 200 과 0 이다`() {
        Mockito.`when`(notificationCommandService.markAllAsRead(meId)).thenReturn(0)

        repeat(2) {
            mockMvc.perform(post("/api/v1/notifications/read-all"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.error").value(false))
                .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
                .andExpect(jsonPath("$.data.unreadCount").value(0))
        }

        Mockito.verify(notificationCommandService, Mockito.times(2)).markAllAsRead(meId)
    }

    /**
     * 🔴 **컨트롤러가 0 을 만들어 넣지 않는다 — 서비스가 준 값을 그대로 싣는다.**
     *
     * 서비스가 갱신 후 실측한 값이 0 이 아니라면 그건 *"UPDATE 가 한 행도 못 건드렸다"* 는 신호이고,
     * 그 신호가 앱까지 가야 한다. 컨트롤러가 `UnreadCountData(0)` 을 하드코딩하면 서비스 쪽
     * 회귀 장치(`NotificationCommandServiceTest`)를 통과하고도 여기서 조용히 0 이 된다.
     */
    @Test
    fun `readAll - 서비스가 0 이 아닌 값을 주면 그대로 실린다`() {
        Mockito.`when`(notificationCommandService.markAllAsRead(meId)).thenReturn(3)

        mockMvc.perform(post("/api/v1/notifications/read-all"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.unreadCount").value(3))
    }

    // ─── 🔴 두 응답에 목록 DTO 가 섞이지 않는다 ──────────────────

    /**
     * 🔴 **`unread-count` 와 `read-all` 응답에 `notifications` 가 없다.**
     *
     * 둘은 `UnreadCountResponse` 하나를 공유하고, 목록은 `NotificationListResponse` 다. 여기에
     * `notifications` 가 새어 나오면 (a) 경로가 목록 매핑으로 잘못 라우팅됐거나 (b) DTO 를 재사용
     * 하려다 필드가 섞인 것이다. 둘 다 앱이 **뱃지 갱신 경로를 두 벌** 갖게 되는 시작점이다.
     */
    @Test
    fun `두 읽음 엔드포인트 응답에는 notifications 가 없다`() {
        Mockito.`when`(notificationQueryService.getUnreadCount(meId)).thenReturn(3)
        Mockito.`when`(notificationCommandService.markAllAsRead(meId)).thenReturn(0)

        val countBody = mockMvc.perform(get("/api/v1/notifications/unread-count"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.notifications").doesNotExist())
            .andExpect(jsonPath("$.data.nextNotificationId").doesNotExist())
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        val readAllBody = mockMvc.perform(post("/api/v1/notifications/read-all"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.notifications").doesNotExist())
            .andExpect(jsonPath("$.data.nextNotificationId").doesNotExist())
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(!countBody.contains("notifications"), "목록 DTO 가 섞였다: $countBody")
        assertTrue(!readAllBody.contains("notifications"), "목록 DTO 가 섞였다: $readAllBody")
        assertTrue(!countBody.contains("isRead"), "읽음 상태가 wire 로 새면 안 된다: $countBody")
        assertTrue(!readAllBody.contains("isRead"), "읽음 상태가 wire 로 새면 안 된다: $readAllBody")
    }

    /** 반대 방향 — 목록 응답에는 `unreadCount` 가 없다. 뱃지 축은 목록이 아니다. */
    @Test
    fun `목록 응답에는 unreadCount 가 없다`() {
        stubDefault(NotificationPage(notifications = listOf(view()), nextNotificationId = null))

        val body = mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.unreadCount").doesNotExist())
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(!body.contains("unreadCount"), "뱃지 축이 목록에도 생기면 갱신 경로가 두 벌이 된다: $body")
    }

    // ─── 인증 (읽음 엔드포인트) ──────────────────────────────────

    /** 목록과 같은 규칙 — SecurityContext 가 비면 `currentUserId()` 가 401 로 끊는다. */
    @Test
    fun `getUnreadCount - 인증 정보가 없으면 HTTP 401 + code 401`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/notifications/unread-count"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))

        Mockito.verify(notificationQueryService, Mockito.never()).getUnreadCount(anyLong())
    }

    /**
     * 🔴 **읽음 처리는 쓰기다 — 인증 실패가 401 로 끊기는 것을 반드시 확인한다.**
     *
     * 여기서 새면 인증 없는 요청이 **남의 수신함을 읽음으로 바꾼다.** 응답만 보면 정상이라
     * shape 단언으로는 절대 안 잡힌다.
     */
    @Test
    fun `readAll - 인증 정보가 없으면 HTTP 401 + code 401`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(post("/api/v1/notifications/read-all"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.UNAUTHORIZED))

        Mockito.verify(notificationCommandService, Mockito.never()).markAllAsRead(anyLong())
    }

    // ─── helpers ─────────────────────────────────────────────────

    private fun verifyServiceNeverCalled() {
        Mockito.verify(notificationQueryService, Mockito.never())
            .getNotifications(anyLong(), any(), anyInt())
    }

    private companion object {
        /** `NotificationController.DEFAULT_SIZE` 와 같은 값이어야 한다 (그쪽은 private 이다). */
        const val DEFAULT_SIZE = 20
    }
}
