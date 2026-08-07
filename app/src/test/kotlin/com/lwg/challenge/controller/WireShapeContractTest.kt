package com.lwg.challenge.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.controller.auth.dto.LoginData
import com.lwg.challenge.controller.auth.dto.LoginResponse
import com.lwg.challenge.controller.auth.dto.RefreshData
import com.lwg.challenge.controller.auth.dto.RefreshResponse
import com.lwg.challenge.controller.challenge.dto.ActiveChallengeDto
import com.lwg.challenge.controller.challenge.dto.ActiveChallengeListData
import com.lwg.challenge.controller.challenge.dto.ActiveChallengeResponse
import com.lwg.challenge.controller.challenge.dto.CancelChallengeData
import com.lwg.challenge.controller.challenge.dto.CancelChallengeResponse
import com.lwg.challenge.controller.challenge.dto.ChallengeCreateData
import com.lwg.challenge.controller.challenge.dto.ChallengeCreateResponse
import com.lwg.challenge.controller.challenge.dto.ChallengeDetailData
import com.lwg.challenge.controller.challenge.dto.ChallengeDetailResponse
import com.lwg.challenge.controller.challenge.dto.ChallengeStatusData
import com.lwg.challenge.controller.challenge.dto.ContractDto
import com.lwg.challenge.controller.challenge.dto.PartyDto
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
import com.lwg.challenge.controller.record.dto.RecordData
import com.lwg.challenge.controller.record.dto.RecordResponse
import com.lwg.challenge.controller.user.dto.UserInfoData
import com.lwg.challenge.controller.user.dto.UserInfoResponse
import com.lwg.challenge.domain.challenge.ChallengeStatus
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
     * `PUT /users/me/fcm-token` 과 `DELETE /auth/logout` 은 **둘 다 `BaseResponse()` 를 그대로**
     * 반환하므로 이 한 건이 두 엔드포인트를 덮는다.
     *
     * ⚠️ *"어느 컨트롤러가 이 타입을 반환하는가"* 는 여기서 알 수 없다 — 그건 슬라이스 테스트
     * (`UserControllerTest` / `AuthControllerTest`)가 응답 원문으로 확인한다.
     */
    @Test
    fun `data 없는 응답 - fcm-token 등록과 logout`() {
        assertNoDataResponse(BaseResponse(), "BaseResponse (fcm-token 등록 / logout 공통)")
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

    @Test
    fun `ActiveChallengeResponse - 홈 카드 8필드`() {
        val data = dataOf(
            ActiveChallengeResponse(
                ActiveChallengeListData(
                    listOf(
                        ActiveChallengeDto(
                            challengeId = 1L,
                            myMission = "m",
                            opponentNickname = "n",
                            opponentMission = "om",
                            deadline = LocalDateTime.of(2026, 8, 7, 0, 0),
                            myVerificationStatus = VerificationStatus.PENDING,
                            opponentVerificationStatus = VerificationStatus.PENDING,
                            bet = "b",
                        ),
                    ),
                ),
            ),
        )
        assertKeys(setOf("activeChallenges"), data, "ActiveChallengeListData")
        assertKeys(
            setOf(
                "challengeId", "myMission", "opponentNickname", "opponentMission",
                "deadline", "myVerificationStatus", "opponentVerificationStatus", "bet",
            ),
            data.path("activeChallenges").first(),
            "ActiveChallengeDto",
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
            setOf("challengeId", "status", "challengeDate", "deadline", "betContent", "challenger", "opponent", "contract"),
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
            setOf("challengeId", "status", "challengeDate", "deadline", "betContent", "challenger", "opponent", "contract"),
            data,
            "ChallengeDetailData (null 최대)",
        )
        assertTrue(data.path("contract").isNull, "contract 키가 있고 값이 null 이어야 한다")
        assertTrue(data.path("challenger").path("mission").isNull, "mission 키가 있고 값이 null 이어야 한다")
    }

    // ─── fixtures ────────────────────────────────────────────────

    private fun detailData() = ChallengeDetailData(
        challengeId = 7L,
        status = ChallengeStatus.IN_PROGRESS,
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
