package com.lwg.challenge.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.controller.auth.dto.LoginData
import com.lwg.challenge.controller.auth.dto.LoginResponse
import com.lwg.challenge.controller.auth.dto.RefreshData
import com.lwg.challenge.controller.auth.dto.RefreshResponse
import com.lwg.challenge.controller.challenge.dto.ActiveChallengeDto
import com.lwg.challenge.controller.challenge.dto.ActiveChallengeListData
import com.lwg.challenge.controller.challenge.dto.ActiveChallengeMeDto
import com.lwg.challenge.controller.challenge.dto.ActiveChallengeOpponentDto
import com.lwg.challenge.controller.challenge.dto.ActiveChallengeResponse
import com.lwg.challenge.controller.challenge.dto.CancelChallengeData
import com.lwg.challenge.controller.challenge.dto.CancelChallengeResponse
import com.lwg.challenge.controller.challenge.dto.ChallengeCreateData
import com.lwg.challenge.controller.challenge.dto.ChallengeCreateResponse
import com.lwg.challenge.controller.challenge.dto.ChallengeDetailData
import com.lwg.challenge.controller.challenge.dto.ChallengeDetailResponse
import com.lwg.challenge.controller.challenge.dto.ChallengeHistoryDto
import com.lwg.challenge.controller.challenge.dto.ChallengeHistoryMonthData
import com.lwg.challenge.controller.challenge.dto.ChallengeHistoryResponse
import com.lwg.challenge.controller.challenge.dto.ChallengeStatusData
import com.lwg.challenge.controller.challenge.dto.ChallengeVerificationsData
import com.lwg.challenge.controller.challenge.dto.ChallengeVerificationsResponse
import com.lwg.challenge.controller.challenge.dto.ContractDto
import com.lwg.challenge.controller.challenge.dto.ForfeitChallengeData
import com.lwg.challenge.controller.challenge.dto.ForfeitChallengeResponse
import com.lwg.challenge.controller.challenge.dto.PartyDto
import com.lwg.challenge.controller.challenge.dto.PartyVerificationDto
import com.lwg.challenge.controller.challenge.dto.SubmitVerificationData
import com.lwg.challenge.controller.challenge.dto.SubmitVerificationResponse
import com.lwg.challenge.controller.challenge.dto.ReceivedChallengeItem
import com.lwg.challenge.controller.challenge.dto.ReceivedChallengesData
import com.lwg.challenge.controller.challenge.dto.ReceivedChallengesResponse
import com.lwg.challenge.controller.common.BaseResponse
import com.lwg.challenge.controller.config.JacksonDateTimeConfig
import com.lwg.challenge.controller.friend.dto.AcceptFriendRequestData
import com.lwg.challenge.controller.friend.dto.AcceptFriendRequestResponse
import com.lwg.challenge.controller.friend.dto.FriendItem
import com.lwg.challenge.controller.friend.dto.FriendsData
import com.lwg.challenge.controller.friend.dto.FriendsResponse
import com.lwg.challenge.controller.friend.dto.FromUser
import com.lwg.challenge.controller.friend.dto.ReceivedFriendRequestItem
import com.lwg.challenge.controller.friend.dto.ReceivedFriendRequestsData
import com.lwg.challenge.controller.friend.dto.ReceivedFriendRequestsResponse
import com.lwg.challenge.controller.friend.dto.SendFriendRequestData
import com.lwg.challenge.controller.friend.dto.SendFriendRequestResponse
import com.lwg.challenge.controller.friend.dto.UserSearchData
import com.lwg.challenge.controller.friend.dto.UserSearchItem
import com.lwg.challenge.controller.friend.dto.UserSearchResponse
import com.lwg.challenge.controller.ranking.dto.LoserRankingData
import com.lwg.challenge.controller.ranking.dto.LoserRankingItem
import com.lwg.challenge.controller.ranking.dto.LoserRankingResponse
import com.lwg.challenge.controller.record.dto.RecordData
import com.lwg.challenge.controller.record.dto.RecordResponse
import com.lwg.challenge.controller.user.dto.UserInfoData
import com.lwg.challenge.controller.user.dto.UserInfoResponse
import com.lwg.challenge.domain.challenge.ChallengeResult
import com.lwg.challenge.domain.challenge.ChallengeStatus
import com.lwg.challenge.domain.challenge.Outcome
import com.lwg.challenge.domain.friend.FriendshipStatus
import com.lwg.challenge.domain.friend.Relation
import com.lwg.challenge.domain.verification.VerificationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 응답 wire shape 회귀 방지 — **필드가 사라지거나 이름이 바뀌면 실패한다** (#25 발견 D).
 *
 * ## 왜 필요한가 — nullable 대조가 못 덮는 축이다
 *
 * `#25` 감사에서 실패 모드가 둘로 갈렸다:
 *
 * | 상황 | 결과 |
 * |---|---|
 * | 서버가 명시적 `null` → 모바일 non-nullable 필드 | **터진다** (soul-oath 2건이 이 경우) |
 * | 서버가 **필드를 제거·개명** | 모바일 기본값으로 **조용히 렌더** |
 *
 * 서버 Jackson 에 `@JsonInclude(NON_NULL)` 설정이 없어 **항상 모든 키를 보낸다**(실측).
 * 따라서 "키 누락"은 **DTO 에서 필드를 지웠을 때만** 생기고, 그건 breaking change 인데
 * **아무도 모르게 지나간다.** nullable 대조는 위 칸만 보고 아래 칸을 못 본다.
 *
 * **이 테스트가 아래 칸을 덮는 유일한 장치다.**
 *
 * ## 왜 컨트롤러 슬라이스가 아니라 DTO 직렬화인가
 *
 * pm-lead 제안은 컨트롤러 슬라이스였는데 **DTO 를 실제 `ObjectMapper` 로 직렬화**하는 쪽을 택했다:
 * - **필드 제거·개명이 일어나는 층이 정확히 DTO 다.** 잡으려는 것 바로 옆에서 잡는다.
 * - 엔드포인트 15개에 mock 을 세우지 않고 **응답 DTO 전수**를 한 번에 덮는다.
 * - 같은 `ObjectMapper`(+ [JacksonDateTimeConfig]) 를 쓰므로 **원문은 동일**하다.
 *
 * ⚠️ **못 덮는 것**: 컨트롤러가 *다른 DTO 로 갈아타는* 경우. 그건 엔드포인트별 슬라이스 테스트
 * (`ChallengeCommandControllerTest` / `ChallengeDetailControllerTest` 등)가 본다.
 *
 * ## 🔴 개명이 제거보다 위험하다 — 모바일에서 **침묵이 두 겹**이다
 *
 * mobile-dev 실측(`KtorfitModule.provideJson()`): `ignoreUnknownKeys = true`,
 * `coerceInputValues` **미설정**(= `false`).
 *
 * 서버가 `betContent` → `bet` 으로 **개명**하면 모바일은:
 * ```
 * betContent 키 없음     →  기본값 "" 사용                 (침묵 1)
 * bet 키 도착(미지 키)   →  ignoreUnknownKeys 로 버림       (침묵 2)
 * 결과: 화면에 빈 내기.  예외 0건, 로그 0건.
 * ```
 * **새 필드가 실제로 도착하는데도 버려진다.** `ignoreUnknownKeys` 자체는 필요하지만(서버가 필드를
 * *추가*할 때 구버전 앱이 안 죽어야 한다) 그 관용이 **개명과 추가를 구별하지 못한다.**
 *
 * 이 테스트가 개명을 잡는 원리: 개명 = **옛 키 소멸 + 새 키 추가**이고 [assertKeys] 의 `missing`
 * 검사가 **소멸 쪽**에서 실패한다. 추가만 있으면 통과한다(의도).
 *
 * ## ⚠️ `RefreshResponse` 가 특히 중요하다
 *
 * mobile-dev 실측: **`/auth/refresh` 응답만 Ktorfit `ApiResult` 경로 밖**이다
 * (`KtorfitModule.kt:80` 에서 Ktor 원시 호출 → `response.body<RefreshResponse>()`).
 * 모바일이 세운 에러 처리·회귀 장치가 **이 경로엔 하나도 안 닿는다.**
 *
 * 가정 시나리오: 서버가 `data` 를 개명 → 모바일이 기본값 `RefreshData()` → `accessToken = ""`
 * → 그런데 `code` 는 200 이라 **성공 분기를 탄다** → `updateTokens("", "")` →
 * **예외도 로그도 없이 로그아웃 상태**(`TokenProviderImpl` 에 blank 가드 없음 — 실측).
 *
 * **서버에서 그 시나리오를 막는 게 아래 `RefreshResponse` 테스트다.**
 *
 * ## 규칙
 *
 * - **키가 사라지거나 이름이 바뀌면 실패한다.**
 * - **키가 늘어나는 것은 실패시키지 않는다** — breaking change 가 아니다.
 *   (다만 새 키가 nullable 이면 계약 §nullable 표에 올려야 한다.)
 * - 기대 집합을 손으로 적는 게 핵심이다. DTO 에서 파생시키면 **DTO 가 바뀔 때 기대도 같이 바뀌어**
 *   아무것도 검증하지 못한다.
 */
@JsonTest
@Import(JacksonDateTimeConfig::class)
class WireShapeContractTest {

    @Autowired
    lateinit var mapper: ObjectMapper

    // ─── 공통 ────────────────────────────────────────────────────

    /** 모든 응답이 [BaseResponse] 를 상속하므로 최상위에 항상 이 넷이 있다. */
    private val baseKeys = setOf("error", "code", "message")

    private fun keysOf(node: JsonNode): Set<String> = node.fieldNames().asSequence().toSet()

    /**
     * 최상위 키가 `data` + BaseResponse 3종인지 확인하고 `data` 를 돌려준다.
     *
     * `error`/`code`/`message` 가 사라지면 **모바일 `ApiResultCall` 이 code 를 못 읽어
     * 성공·실패 분기가 통째로 무너진다.** 그래서 매 응답마다 확인한다.
     */
    private fun dataOf(response: Any): JsonNode {
        val root = mapper.readTree(mapper.writeValueAsString(response))
        assertEquals(
            baseKeys + "data",
            keysOf(root),
            "최상위 키가 바뀌었다 — BaseResponse 상속이 깨졌거나 data 가 개명됐다",
        )
        return root.path("data")
    }

    /**
     * `data` 를 **갖지 않는** 응답의 wire shape 고정 (push-fcm).
     *
     * [dataOf] 를 쓸 수 없다 — 그쪽은 최상위 키를 `baseKeys + "data"` 로 단언하므로 여기서 쓰면
     * 오히려 실패한다. 두 헬퍼가 갈라져 있는 것 자체가 **"data 가 있는 응답"과 "없는 응답"이
     * 서로 다른 계약**이라는 사실을 코드로 드러낸다.
     *
     * 🔴 **`data` 는 없는 것이지 `null` 인 것이 아니다.** 이 구분이 모바일에서 다른 예외가 된다:
     * - 키 자체가 없음 → 기본값 없으면 `MissingFieldException`
     * - 키가 있고 값이 `null` → non-nullable 이면 `JsonConvertException`
     *
     * push-fcm 계약 초안이 `"data": null` 이라고 **서버가 보내지 않는 키를 보낸다고 적었다가**
     * 정정된 지점이라, 그 정정을 wire 로 잠근다.
     */
    private fun assertNoDataResponse(response: BaseResponse, where: String) {
        val root = mapper.readTree(mapper.writeValueAsString(response))
        assertEquals(
            baseKeys,
            keysOf(root),
            "$where — data 없는 응답에 키가 생기거나 사라졌다. `data` 를 추가했다면 계약(§1/§2)과 모바일 DTO 를 함께 고쳐라",
        )
    }

    private fun assertKeys(expected: Set<String>, actual: JsonNode, where: String) {
        val got = keysOf(actual)
        val missing = expected - got
        assertTrue(
            missing.isEmpty(),
            "$where — 🔴 키가 사라졌다(=breaking change): $missing / 현재 키=$got",
        )
        // 늘어난 키는 실패시키지 않는다. 다만 눈에는 띄게 남긴다.
        val added = got - expected
        if (added.isNotEmpty()) {
            println("ℹ️  $where — 키가 추가됐다(허용): $added. nullable 이면 계약 표에 올려라.")
        }
    }

    // ─── auth ────────────────────────────────────────────────────

    @Test
    fun `LoginResponse - 로그인 4필드`() {
        val data = dataOf(LoginResponse(LoginData("a", "r", 1L, true)))
        assertKeys(setOf("accessToken", "refreshToken", "userId", "isNewUser"), data, "LoginData")
    }

    @Test
    fun `RefreshResponse - 토큰 2필드`() {
        val data = dataOf(RefreshResponse(RefreshData("a", "r")))
        assertKeys(setOf("accessToken", "refreshToken"), data, "RefreshData")
    }

    /**
     * `PUT /users/me/fcm-token`, `DELETE /auth/logout`, `DELETE /users/me`(회원탈퇴 — mypage T-B2)
     * 는 **전부 `BaseResponse()` 를 그대로** 반환하므로 이 한 건이 세 엔드포인트를 덮는다.
     *
     * 🔴 새 엔드포인트를 위해 **같은 단언을 복제하지 않는다.** 고정하려는 것은 "누가 반환하는가"
     * 가 아니라 **타입 하나의 wire shape** 이라, 테스트를 늘리면 대상은 그대로인데 실패 지점만
     * 늘어난다. 합류시키고 이름·설명을 갱신하는 것이 맞다.
     *
     * ⚠️ *"어느 컨트롤러가 이 타입을 반환하는가"* 는 여기서 알 수 없다 — 그건 슬라이스 테스트
     * (`UserControllerTest` / `AuthControllerTest`)가 응답 원문으로 확인한다.
     */
    @Test
    fun `data 없는 응답 - fcm-token 등록과 logout 과 회원탈퇴`() {
        assertNoDataResponse(BaseResponse(), "BaseResponse (fcm-token 등록 / logout / 회원탈퇴 공통)")
    }

    // ─── user / record ───────────────────────────────────────────

    @Test
    fun `UserInfoResponse - 4필드`() {
        val data = dataOf(UserInfoResponse(UserInfoData(1L, 10L, "닉", null)))
        assertKeys(setOf("id", "kakaoId", "nickname", "profileImageUrl"), data, "UserInfoData")
    }

    @Test
    fun `RecordResponse - 전적 4필드`() {
        val data = dataOf(RecordResponse(RecordData(1, 2, 3, 4)))
        assertKeys(setOf("win", "lose", "draw", "currentStreak"), data, "RecordData")
    }

    // ─── ranking ─────────────────────────────────────────────────

    /**
     * 🔴 `profileImageUrl = null` 인 항목으로 고정한다 — 랭킹 목록에서 **프로필 없는 사용자가
     * 다수**라 이 필드는 대부분의 항목에서 null 로 흐른다. `@JsonInclude(NON_NULL)` 이 켜지면
     * 그 항목들에서 키가 통째로 사라지고, 모바일 실패 모드가 `JsonConvertException` →
     * `MissingFieldException` 으로 조용히 바뀐다 (#24 와 같은 축).
     *
     * `isMe` 도 함께 못박는다 — 앱이 "나" 를 찾는 유일한 신호가 이 키다(userId 대조가 아니라).
     * 사라지면 Kotlin `Boolean` 기본값이 없어 파싱이 터지거나, 있어도 아무도 강조되지 않는다.
     */
    @Test
    fun `LoserRankingResponse - 랭킹 목록 + 항목 9필드`() {
        val response = LoserRankingResponse(
            LoserRankingData(
                listOf(
                    LoserRankingItem(
                        rank = 1,
                        userId = 3L,
                        nickname = "준혁",
                        profileImageUrl = null,
                        isMe = false,
                        losses = 25,
                        lossRate = 78,
                        currentLossStreak = 7,
                        totalChallenges = 32,
                    ),
                    LoserRankingItem(
                        rank = 2,
                        userId = 1L,
                        nickname = "나",
                        profileImageUrl = "https://cdn.example.com/p/1.jpg",
                        isMe = true,
                        losses = 12,
                        lossRate = 30,
                        currentLossStreak = 0,
                        totalChallenges = 40,
                    ),
                ),
            ),
        )
        val data = dataOf(response)
        assertKeys(setOf("rankings"), data, "LoserRankingData")

        val item = data.path("rankings").first()
        assertKeys(
            setOf(
                "rank", "userId", "nickname", "profileImageUrl",
                "isMe", "losses", "lossRate", "currentLossStreak",
                "totalChallenges",
            ),
            item,
            "LoserRankingItem",
        )
        assertTrue(item.path("profileImageUrl").isNull, "profileImageUrl 키가 있고 값이 null 이어야 한다")
        // 키 부재와 값 null 은 모바일에서 서로 다른 예외라 원문까지 확인한다 (#24 선례).
        assertTrue(
            mapper.writeValueAsString(response).contains(""""profileImageUrl":null"""),
            "원문에 profileImageUrl 키가 null 값으로 남아 있어야 한다",
        )
    }

    // ─── friends ─────────────────────────────────────────────────

    @Test
    fun `FriendsResponse - 목록 + 항목 4필드`() {
        val data = dataOf(
            FriendsResponse(FriendsData(listOf(FriendItem(1L, "닉", null, LocalDateTime.of(2026, 8, 6, 0, 0))))),
        )
        assertKeys(setOf("friends"), data, "FriendsData")
        assertKeys(setOf("id", "nickname", "profileImageUrl", "since"), data.path("friends").first(), "FriendItem")
    }

    @Test
    fun `ReceivedFriendRequestsResponse - 목록 + 항목 + fromUser`() {
        val data = dataOf(
            ReceivedFriendRequestsResponse(
                ReceivedFriendRequestsData(
                    listOf(
                        ReceivedFriendRequestItem(
                            id = 1L,
                            fromUser = FromUser(2L, "닉", null),
                            requestedAt = LocalDateTime.of(2026, 8, 6, 0, 0),
                        ),
                    ),
                ),
            ),
        )
        assertKeys(setOf("requests"), data, "ReceivedFriendRequestsData")
        val item = data.path("requests").first()
        assertKeys(setOf("id", "fromUser", "requestedAt"), item, "ReceivedFriendRequestItem")
        assertKeys(setOf("id", "nickname", "profileImageUrl"), item.path("fromUser"), "FromUser")
    }

    @Test
    fun `UserSearchResponse - 검색 항목 5필드`() {
        val data = dataOf(
            UserSearchResponse(UserSearchData(listOf(UserSearchItem(1L, "닉", null, Relation.NONE, null)))),
        )
        assertKeys(setOf("users"), data, "UserSearchData")
        assertKeys(
            setOf("id", "nickname", "profileImageUrl", "relation", "pendingRequestId"),
            data.path("users").first(),
            "UserSearchItem",
        )
    }

    @Test
    fun `친구 요청 3종 응답`() {
        assertKeys(
            setOf("requestId", "status"),
            dataOf(SendFriendRequestResponse(SendFriendRequestData(1L, FriendshipStatus.PENDING))),
            "SendFriendRequestData",
        )
        assertKeys(
            setOf("friendshipId", "status"),
            dataOf(AcceptFriendRequestResponse(AcceptFriendRequestData(1L, FriendshipStatus.ACCEPTED))),
            "AcceptFriendRequestData",
        )
    }

    // ─── challenges ──────────────────────────────────────────────

    /**
     * 🔴 **진행 중 카드로 고정한다** — `myResult` 가 **null 인 인스턴스**다.
     *
     * `myResult` 는 홈 카드의 **다수(진행 중)에서 항상 null** 이다. 즉 이 응답에서 실제로 흐르는 값은 대부분 null 이고,
     * `@JsonInclude(NON_NULL)` 이 켜지는 순간 **카드 대부분에서 키가 통째로 사라진다** —
     * 모바일 실패 모드가 `JsonConvertException` → `MissingFieldException` 으로 조용히 바뀐다(#24).
     * 값이 채워진 인스턴스로 고정하면 그 사고를 이 테스트가 못 본다.
     */
    @Test
    fun `ActiveChallengeResponse - 홈 카드 7필드 + me·opponent 객체 (진행 중 = myResult null)`() {
        val response = ActiveChallengeResponse(
            ActiveChallengeListData(
                listOf(
                    ActiveChallengeDto(
                        challengeId = 1L,
                        status = ChallengeStatus.IN_PROGRESS,
                        myResult = null,
                        deadline = LocalDateTime.of(2026, 8, 7, 0, 0),
                        bet = "b",
                        me = ActiveChallengeMeDto(mission = "m", verificationStatus = VerificationStatus.PENDING),
                        opponent = ActiveChallengeOpponentDto(
                            nickname = "n",
                            mission = "om",
                            verificationStatus = VerificationStatus.PENDING,
                        ),
                    ),
                ),
            ),
        )
        val data = dataOf(response)
        assertKeys(setOf("activeChallenges"), data, "ActiveChallengeListData")

        val card = data.path("activeChallenges").first()
        assertKeys(
            setOf(
                "challengeId", "status", "myResult", "deadline", "bet", "me", "opponent",
            ),
            card,
            "ActiveChallengeDto",
        )
        assertKeys(setOf("mission", "verificationStatus"), card.path("me"), "ActiveChallengeMeDto")
        assertKeys(
            setOf("nickname", "mission", "verificationStatus"),
            card.path("opponent"),
            "ActiveChallengeOpponentDto",
        )
        // status 는 non-null 이다 — 카드가 결과 표시로 갈아탈 유일한 신호라 값까지 본다 (계약 §2.2).
        assertEquals("IN_PROGRESS", card.path("status").asText())
        assertTrue(card.path("myResult").isNull, "myResult 키가 있고 값이 null 이어야 한다")
        // 키 부재와 값 null 은 모바일에서 서로 다른 예외라 원문까지 확인한다 (#24 선례).
        val raw = mapper.writeValueAsString(response)
        assertTrue(raw.contains(""""myResult":null"""), "원문에 myResult 가 null 값으로 남아야 한다. actual=$raw")
    }

    /**
     * 판정된 카드의 `myResult`(내 시점).
     *
     * 🔴 `myResult` 는 `LOSE` 로 접히지 않고 `"BOTH_LOSE"` 로 나간다(모바일 협의 확정 — `Outcome` KDoc).
     * 역할 기준 `result` 는 이 응답에서 제거됐다 — 내가 challenger 인지 알 수 없어 해석 불가.
     */
    @Test
    fun `ActiveChallengeResponse - 판정된 카드는 myResult 가 BOTH_LOSE 그대로 나간다`() {
        val data = dataOf(
            ActiveChallengeResponse(
                ActiveChallengeListData(
                    listOf(
                        ActiveChallengeDto(
                            challengeId = 2L,
                            status = ChallengeStatus.COMPLETED,
                            myResult = Outcome.BOTH_LOSE,
                            deadline = LocalDateTime.of(2026, 8, 7, 0, 0),
                            bet = "b",
                            me = ActiveChallengeMeDto(mission = "m", verificationStatus = VerificationStatus.FAILED),
                            opponent = ActiveChallengeOpponentDto(
                                nickname = "n",
                                mission = "om",
                                verificationStatus = VerificationStatus.FAILED,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val card = data.path("activeChallenges").first()
        assertEquals("COMPLETED", card.path("status").asText())
        assertEquals("BOTH_LOSE", card.path("myResult").asText())
    }

    /**
     * 계약서 보관함 — 한 달치 (mypage T-B1, 2026-08-28 월 단위 개정).
     *
     * 🔴 **data 가 3키인 것과 카드가 7필드인 것이 각각 계약이다.**
     *
     * data 쪽: 1차는 `histories` 하나였고 개정으로 `month`·`firstArchivedMonth` 가 붙었다.
     * 이 둘이 사라지면 앱이 **월 표시와 화살표를 그릴 근거를 통째로 잃는다** — 목록은 멀쩡히
     * 나가므로 jsonPath 로 목록만 보는 테스트는 그 사고를 통과시킨다.
     *
     * 카드 쪽(design §7-④): 이 테스트는 원래 *"키가 사라지면 실패"* 만 보지만, 여기서는
     * `added` 쪽도 눈여겨봐야 한다 — 홈 카드와 모양을 맞추자며 `result`(역할 기준)나 인증
     * 상태 2필드를 되살리기 쉬운 자리이고, 그건 **추가가 아니라 결정의 번복**이다.
     * 그 축은 `ChallengeHistoryControllerTest` 가 원문으로 막는다.
     *
     * 세 시간 필드(`month`·`firstArchivedMonth`·`challengeDate`)는 값까지 본다 —
     * `[2026,8]` / `[2026,8,24]` **배열**로 새는 회귀가 이 프로젝트에 실제로 있었던 실패 모드다.
     */
    @Test
    fun `ChallengeHistoryResponse - 월 3키 + 카드 7필드`() {
        val response = ChallengeHistoryResponse(
            ChallengeHistoryMonthData(
                month = YearMonth.of(2026, 8),
                firstArchivedMonth = YearMonth.of(2026, 3),
                histories = listOf(
                    ChallengeHistoryDto(
                        challengeId = 1001L,
                        challengeDate = LocalDate.of(2026, 8, 24),
                        opponentNickname = "민수",
                        myMission = "오늘 운동 1시간 하기",
                        opponentMission = "책 30페이지 읽기",
                        bet = "커피 사기",
                        myResult = Outcome.BOTH_LOSE,
                    ),
                ),
            ),
        )
        val data = dataOf(response)
        assertKeys(setOf("month", "firstArchivedMonth", "histories"), data, "ChallengeHistoryMonthData")

        // 🔴 `yyyy-MM` 평문 문자열. WIRE_MONTH 가 빠지면 `[2026,8]` 배열이 된다.
        assertEquals("2026-08", data.path("month").asText())
        assertEquals("2026-03", data.path("firstArchivedMonth").asText())

        val card = data.path("histories").first()
        assertKeys(
            setOf(
                "challengeId", "challengeDate", "opponentNickname",
                "myMission", "opponentMission", "bet", "myResult",
            ),
            card,
            "ChallengeHistoryDto",
        )
        assertEquals("2026-08-24", card.path("challengeDate").asText())
        // 🔴 BOTH_LOSE 는 LOSE 로 접히지 않고 그대로 나간다 (`Outcome` KDoc).
        assertEquals("BOTH_LOSE", card.path("myResult").asText())
    }

    /**
     * 🔴 보관함은 **빌 수 있다** — `rankings` 가 *"절대 비지 않는다"* 인 것과 반대 축이라
     * 빈 배열이 실제로 직렬화되는지까지 본다. 모바일의 빈 상태 판정이 여기 달려 있다.
     *
     * 개정 후에는 이 경우가 **더 흔해졌다** — 빈 달을 건너뛰지 않는 것이 확정된 동작이라
     * 사용자가 화살표를 누르는 대부분의 달이 `[]` 다. 그리고 그때도 `month`·`firstArchivedMonth`
     * 는 함께 나가야 한다.
     */
    @Test
    fun `ChallengeHistoryResponse - 빈 달도 month 와 하한 신호를 함께 낸다`() {
        val response = ChallengeHistoryResponse(
            ChallengeHistoryMonthData(
                month = YearMonth.of(2026, 4),
                firstArchivedMonth = YearMonth.of(2026, 3),
                histories = emptyList(),
            ),
        )
        val data = dataOf(response)

        assertKeys(setOf("month", "firstArchivedMonth", "histories"), data, "ChallengeHistoryMonthData (빈 달)")
        assertEquals("2026-04", data.path("month").asText())
        assertEquals("2026-03", data.path("firstArchivedMonth").asText())
        assertTrue(
            mapper.writeValueAsString(response).contains(""""histories":[]"""),
            "원문에 histories 가 빈 배열로 남아 있어야 한다",
        )
    }

    /**
     * 🔴 **`firstArchivedMonth` 가 `null` 이어도 키는 남는다** — 기록 0건 계정.
     *
     * 이 프로젝트 Jackson 에 `@JsonInclude(NON_NULL)` 이 없다는 것(이 파일 최상단 KDoc 실측)에
     * 모바일이 기대고 있다. 나중에 누가 전역으로 `NON_NULL` 을 켜면 **키가 조용히 사라지고**,
     * 앱은 *"기록 전무"* 를 판정할 신호를 잃은 채 빈 달 화면을 그린다 — 목록은 멀쩡히 `[]` 라
     * 다른 어떤 단언도 이걸 못 잡는다.
     */
    @Test
    fun `ChallengeHistoryResponse - firstArchivedMonth 는 null 이어도 키가 남는다`() {
        val response = ChallengeHistoryResponse(
            ChallengeHistoryMonthData(
                month = YearMonth.of(2026, 8),
                firstArchivedMonth = null,
                histories = emptyList(),
            ),
        )

        assertKeys(setOf("month", "firstArchivedMonth", "histories"), dataOf(response), "ChallengeHistoryMonthData (기록 0건)")
        assertTrue(
            mapper.writeValueAsString(response).contains(""""firstArchivedMonth":null"""),
            "null 이어도 키가 원문에 남아 있어야 한다",
        )
    }

    @Test
    fun `ReceivedChallengesResponse - 받은 도전장 8필드`() {
        val data = dataOf(
            ReceivedChallengesResponse(
                ReceivedChallengesData(
                    listOf(
                        ReceivedChallengeItem(
                            challengeId = 1L,
                            challengerId = 2L,
                            challengerNickname = "n",
                            challengerProfileImageUrl = null,
                            challengerMission = "m",
                            betContent = "b",
                            challengeDate = LocalDate.of(2026, 8, 6),
                            deadline = LocalDateTime.of(2026, 8, 7, 0, 0),
                            createdAt = LocalDateTime.of(2026, 8, 6, 12, 0),
                        ),
                    ),
                ),
            ),
        )
        assertKeys(setOf("challenges"), data, "ReceivedChallengesData")
        assertKeys(
            setOf(
                "challengeId", "challengerId", "challengerNickname", "challengerProfileImageUrl",
                "challengerMission", "betContent", "challengeDate", "deadline", "createdAt",
            ),
            data.path("challenges").first(),
            "ReceivedChallengeItem",
        )
    }

    @Test
    fun `챌린지 명령 응답 3종`() {
        assertKeys(
            setOf("challengeId", "status", "challengeDate", "deadline"),
            dataOf(
                ChallengeCreateResponse(
                    ChallengeCreateData(1L, ChallengeStatus.PENDING, LocalDate.of(2026, 8, 6), LocalDateTime.of(2026, 8, 7, 0, 0)),
                ),
            ),
            "ChallengeCreateData",
        )
        assertKeys(
            setOf("challengeId", "status"),
            dataOf(
                com.lwg.challenge.controller.challenge.dto.AcceptChallengeResponse(
                    ChallengeStatusData(1L, ChallengeStatus.IN_PROGRESS),
                ),
            ),
            "ChallengeStatusData",
        )
        assertKeys(
            setOf("challengeId"),
            dataOf(CancelChallengeResponse(CancelChallengeData(1L))),
            "CancelChallengeData",
        )
    }

    @Test
    fun `ChallengeDetailResponse - 상세 + 당사자 + 계약서`() {
        val data = dataOf(ChallengeDetailResponse(detailData()))
        assertKeys(
            setOf(
                "challengeId", "status", "result", "forfeitedBy", "challengeDate", "deadline",
                "betContent", "challenger", "opponent", "contract",
            ),
            data,
            "ChallengeDetailData",
        )
        assertKeys(setOf("userId", "nickname", "profileImageUrl", "mission"), data.path("challenger"), "PartyDto")
        assertKeys(
            setOf(
                "content", "isFinalized",
                "challengerSignature", "challengerSignedAt",
                "opponentSignature", "opponentSignedAt",
            ),
            data.path("contract"),
            "ContractDto",
        )
    }

    /**
     * 🔴 `contract: null` 이어도 **키는 남아 있어야 한다** (#24).
     *
     * 누가 `@JsonInclude(NON_NULL)` 을 켜면 키가 통째로 사라지고, 모바일 실패 모드가
     * `JsonConvertException` → `MissingFieldException` 으로 **조용히 바뀐다.**
     */
    @Test
    fun `null 인 필드도 키는 남는다`() {
        val data = dataOf(
            ChallengeDetailResponse(
                detailData().copy(
                    contract = null,
                    challenger = detailData().challenger.copy(profileImageUrl = null, mission = null),
                ),
            ),
        )
        assertKeys(
            setOf(
                "challengeId", "status", "result", "forfeitedBy", "challengeDate", "deadline",
                "betContent", "challenger", "opponent", "contract",
            ),
            data,
            "ChallengeDetailData (null 최대)",
        )
        assertTrue(data.path("contract").isNull, "contract 키가 있고 값이 null 이어야 한다")
        assertTrue(data.path("challenger").path("mission").isNull, "mission 키가 있고 값이 null 이어야 한다")
        // [detailData] 는 IN_PROGRESS 라 result 가 null 이다 — 상세에서도 **대부분의 응답이
        // null 인 필드**이므로 여기서 키 존재를 못박는다 (계약 §1: status != COMPLETED ⇒ 항상 null).
        assertTrue(data.path("result").isNull, "result 키가 있고 값이 null 이어야 한다")
        // 기권 없는 챌린지가 압도적 다수라 이 키도 거의 항상 null 이다 (challenge-forfeit).
        // 키가 사라지면 앱은 "기권 없음" 과 "필드 자체가 없음" 을 구별하지 못한다.
        assertTrue(data.path("forfeitedBy").isNull, "forfeitedBy 키가 있고 값이 null 이어야 한다")
    }

    /**
     * 기권 응답 5필드 (challenge-forfeit 계약 §1).
     *
     * 🔴 `myResult` 는 오늘 **항상 `LOSE`** 라 *"상수를 왜 보내나"* 라는 말이 나올 수 있는 필드다.
     * 지우면 앱이 `result` 를 역할로 뒤집거나 *"기권했으니 졌다"* 를 앱에 심게 되고, 그게
     * `challenge-result` 가 `myResult` 를 신설하며 막았던 사본이다 — **지우지 마라.**
     */
    @Test
    fun `ForfeitChallengeResponse - 기권 확정 5필드`() {
        val data = dataOf(
            ForfeitChallengeResponse(
                ForfeitChallengeData(
                    challengeId = 1001L,
                    status = ChallengeStatus.COMPLETED,
                    result = ChallengeResult.OPPONENT_WIN,
                    myResult = Outcome.LOSE,
                    completedAt = LocalDateTime.of(2026, 9, 5, 14, 23, 11),
                ),
            ),
        )
        assertKeys(
            setOf("challengeId", "status", "result", "myResult", "completedAt"),
            data,
            "ForfeitChallengeData",
        )
        // ADR-0010 — `T` 도 `Z` 도 오프셋도 밀리초도 없다. 모바일 파서가 `T` 를 받으면 실패한다.
        assertEquals("2026-09-05 14:23:11", data.path("completedAt").asText(), "completedAt 표기")
        assertEquals("COMPLETED", data.path("status").asText(), "기권은 즉시 확정이다")
    }

    // ─── verification (challenge-verification) ───────────────────

    @Test
    fun `SubmitVerificationResponse - 제출 확정 3필드`() {
        val data = dataOf(
            SubmitVerificationResponse(
                SubmitVerificationData(7L, VerificationStatus.VERIFIED, LocalDateTime.of(2026, 8, 14, 15, 32, 10)),
            ),
        )
        assertKeys(setOf("challengeId", "status", "verifiedAt"), data, "SubmitVerificationData")
        assertEquals("2026-08-14 15:32:10", data.path("verifiedAt").asText())
    }

    @Test
    fun `ChallengeVerificationsResponse - 양측 인증 현황`() {
        val data = dataOf(ChallengeVerificationsResponse(verificationsData()))
        assertKeys(setOf("challengeId", "challenger", "opponent"), data, "ChallengeVerificationsData")
        assertKeys(
            setOf("userId", "status", "photoUrl", "verifiedAt", "photoDeleted", "aiReason"),
            data.path("challenger"),
            "PartyVerificationDto",
        )
        assertEquals("2026-08-14 15:32:10", data.path("challenger").path("verifiedAt").asText())
    }

    /**
     * `photoDeleted` 는 **non-null Boolean 이고 기본이 `false`** 다 (mypage T-B1 부록 A —
     * challenge-verification 계약의 additive 변경).
     *
     * 🔴 값까지 보는 이유: 이 필드가 `true` 로 새면 앱이 정상 사진 자리를 *"탈퇴로 삭제됨"* 으로
     * 그린다. 그리고 **키가 사라지면 모바일 `Boolean` 에 기본값이 없어 파싱이 터진다** —
     * nullable 이 아니라서 `@JsonInclude` 류의 설정 변화에 오히려 더 약하다.
     */
    @Test
    fun `PartyVerificationDto - photoDeleted 는 키가 있고 기본이 false 다`() {
        val response = ChallengeVerificationsResponse(verificationsData())
        val data = dataOf(response)

        assertTrue(data.path("challenger").path("photoDeleted").isBoolean, "photoDeleted 는 Boolean 이어야 한다")
        assertEquals(false, data.path("challenger").path("photoDeleted").asBoolean())
        assertEquals(false, data.path("opponent").path("photoDeleted").asBoolean())
        assertTrue(
            mapper.writeValueAsString(response).contains(""""photoDeleted":false"""),
            "원문에 photoDeleted 키가 false 로 남아 있어야 한다",
        )
    }

    /** 탈퇴로 사진이 지워진 쪽은 `photoUrl = null` 인 채 `photoDeleted = true` 로 나간다. */
    @Test
    fun `PartyVerificationDto - 탈퇴로 지워진 사진은 photoUrl null 에 photoDeleted true 다`() {
        val response = ChallengeVerificationsResponse(
            verificationsData().copy(
                challenger = verificationsData().challenger.copy(photoUrl = null, photoDeleted = true),
            ),
        )
        val challenger = dataOf(response).path("challenger")

        assertEquals("VERIFIED", challenger.path("status").asText(), "탈퇴는 row 를 보존한다 — status 는 그대로다")
        assertTrue(challenger.path("photoUrl").isNull, "key 가 지워졌으므로 경로도 null 이다")
        assertEquals(true, challenger.path("photoDeleted").asBoolean())
    }

    /**
     * 🔴 아직 인증하지 않은 쪽은 두 필드가 전부 `null` 이다. **키는 남아야 한다** —
     * `@JsonInclude(NON_NULL)` 이 켜지면 모바일 실패 모드가 조용히 바뀐다 (#24 와 같은 축).
     *
     * 키 부재와 값 null 은 모바일에서 서로 다른 예외라 **원문에 `"photoUrl":null` 이 실제로
     * 있는지**까지 본다 (#24 선례).
     */
    @Test
    fun `PENDING 인 쪽도 photoUrl 키가 null 로 남는다`() {
        val response = ChallengeVerificationsResponse(verificationsData())
        val opponent = dataOf(response).path("opponent")

        assertKeys(
            setOf("userId", "status", "photoUrl", "verifiedAt", "photoDeleted", "aiReason"),
            opponent,
            "PartyVerificationDto (PENDING)",
        )
        assertTrue(opponent.path("photoUrl").isNull, "photoUrl 키가 있고 값이 null 이어야 한다")
        assertTrue(opponent.path("verifiedAt").isNull)
        assertTrue(opponent.path("aiReason").isNull, "aiReason 키가 있고 값이 null 이어야 한다")
        assertTrue(
            mapper.writeValueAsString(response).contains(""""photoUrl":null"""),
            "원문에 photoUrl 키가 null 값으로 남아 있어야 한다",
        )
        assertTrue(
            mapper.writeValueAsString(response).contains(""""aiReason":null"""),
            "원문에 aiReason 키가 null 값으로 남아 있어야 한다",
        )
    }

    /**
     * 🔴 **AI 반려는 `status = FAILED` 에 사유와 사진 경로가 함께 나간다** (ai-verification).
     *
     * 이 조합이 이 feature 가 wire 에 만든 **유일한 새 상태**다. 예전에는 `FAILED` 면 `photoUrl`
     * 이 항상 null 이었으므로(미제출로 마감), **앱이 그 낡은 규칙을 코드에 굳혀 두면 반려된
     * 사진을 안 그린다.** 계약이 이 조합을 허용한다는 것을 shape 으로 못박는다.
     */
    @Test
    fun `PartyVerificationDto - AI 반려는 FAILED 에 사유와 사진 경로가 함께 나간다`() {
        val response = ChallengeVerificationsResponse(
            verificationsData().copy(
                challenger = verificationsData().challenger.copy(
                    status = VerificationStatus.FAILED,
                    aiReason = "책상 위 커피잔 사진으로, 미션(주 3회 운동) 수행 증거로 보기 어려워요.",
                ),
            ),
        )
        val challenger = dataOf(response).path("challenger")

        assertEquals("FAILED", challenger.path("status").asText())
        assertTrue(challenger.path("aiReason").isTextual, "반려 사유가 문자열로 나가야 한다")
        assertTrue(
            !challenger.path("photoUrl").isNull,
            "반려돼도 사진 경로는 남는다 — 판정의 근거를 숨기면 사용자가 무엇이 반려됐는지 못 본다",
        )
        assertEquals(false, challenger.path("photoDeleted").asBoolean(), "반려는 삭제가 아니다")
    }

    // ─── fixtures ────────────────────────────────────────────────

    private fun verificationsData() = ChallengeVerificationsData(
        challengeId = 7L,
        challenger = PartyVerificationDto(
            userId = 1L,
            status = VerificationStatus.VERIFIED,
            photoUrl = "/api/v1/challenges/7/photos/challenger",
            verifiedAt = LocalDateTime.of(2026, 8, 14, 15, 32, 10),
            // 정상 사용자 — 사진이 살아 있다. 탈퇴 케이스는 별도 테스트에서 copy 로 만든다.
            photoDeleted = false,
            // 통과했거나 판별 전이다. 반려 케이스는 별도 테스트에서 copy 로 만든다.
            aiReason = null,
        ),
        opponent = PartyVerificationDto(
            userId = 14L,
            status = VerificationStatus.PENDING,
            photoUrl = null,
            verifiedAt = null,
            photoDeleted = false,
            aiReason = null,
        ),
    )

    private fun detailData() = ChallengeDetailData(
        challengeId = 7L,
        status = ChallengeStatus.IN_PROGRESS,
        // IN_PROGRESS 이므로 null 이다 — `status == COMPLETED ⟺ result != null` (계약 §1).
        result = null,
        // 기권 없이 진행 중이다 — null 이어도 키는 나간다 (challenge-forfeit).
        forfeitedBy = null,
        challengeDate = "2026-08-03",
        deadline = LocalDateTime.of(2026, 8, 4, 0, 0),
        betContent = "커피 사기",
        challenger = PartyDto(1L, "이우건", null, "운동"),
        opponent = PartyDto(14L, "테스터1", null, "독서"),
        contract = ContractDto(
            content = "본문",
            isFinalized = true,
            challengerSignature = """{"v":1,"g":1000,"s":[[1,2]]}""",
            challengerSignedAt = LocalDateTime.of(2026, 8, 3, 11, 20),
            opponentSignature = """{"v":1,"g":1000,"s":[[3,4]]}""",
            opponentSignedAt = LocalDateTime.of(2026, 8, 3, 11, 25),
        ),
    )
}
