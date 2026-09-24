package com.lwg.challenge.controller.challenge

import com.lwg.challenge.controller.common.exception.GlobalExceptionHandler
import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.OneButtonDialogException
import com.lwg.challenge.domain.common.exception.SnackbarException
import com.lwg.challenge.domain.verification.Verification
import com.lwg.challenge.domain.verification.VerificationStatus
import com.lwg.challenge.service.verification.PartyVerificationView
import com.lwg.challenge.service.verification.PhotoReadResult
import com.lwg.challenge.service.verification.VerificationParty
import com.lwg.challenge.service.verification.VerificationService
import com.lwg.challenge.service.verification.VerificationsView
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/**
 * [VerificationController] 슬라이스 테스트 (challenge-verification T-B2·T-B3).
 *
 * ## 무엇을 검증하는가
 *
 * 서비스 로직은 `VerificationServiceTest` 가 덮는다. 여기서 보는 것은 **wire 로 나가는 모양**이다:
 * - 제출이 **multipart part `photo`** 로 들어온다 (ADR-0011 §3 개정) — part 이름이 다르면 700
 * - `verifiedAt` 이 ADR-0010 표기(`yyyy-MM-dd HH:mm:ss`, `T` 없음)로 나가는가
 * - 🔴 아직 인증 안 한 쪽의 `photoUrl` 이 **키를 남긴 채 null** 인가 (#24 와 같은 축)
 * - 비즈니스 에러가 **HTTP 200 + body.code** 로 나가는가 (ADR-0002)
 *
 * ⚠️ 서비스가 mock 이므로 바이트 내용(JPEG 검사 등)은 여기서 의미가 없다 — 그건
 * `VerificationServiceTest` 의 몫이다. 여기서 고정하는 것은 **part 이름이 `photo`** 라는 계약이다.
 */
@WebMvcTest(controllers = [VerificationController::class])
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class VerificationControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var verificationService: VerificationService

    private val meId = 1L

    private val jpegPart = MockMultipartFile(
        "photo",
        "photo.jpg",
        "image/jpeg",
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x11),
    )

    @BeforeEach
    fun setUpAuthentication() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(meId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
    }

    // Mockito 매처는 스터빙 시점에 null 을 반환하는데 photo 파라미터가 Kotlin non-null 이라 그대로 쓰면 NPE 가 난다.
    private fun anyBytes(): ByteArray = ArgumentMatchers.any(ByteArray::class.java) ?: ByteArray(0)

    // 같은 이유 — party 파라미터가 Kotlin non-null enum 이다.
    private fun anyParty(): VerificationParty =
        ArgumentMatchers.any(VerificationParty::class.java) ?: VerificationParty.CHALLENGER

    private fun stubReadPhoto(result: PhotoReadResult) {
        Mockito.`when`(verificationService.readPhoto(anyLong(), anyLong(), anyParty()))
            .thenReturn(result)
    }

    private fun stubSubmit(result: Verification) {
        Mockito.`when`(verificationService.submit(anyLong(), anyLong(), anyBytes()))
            .thenReturn(result)
    }

    private fun stubSubmitThrows(e: Throwable) {
        Mockito.`when`(verificationService.submit(anyLong(), anyLong(), anyBytes()))
            .thenThrow(e)
    }

    // ─── §3 제출 ─────────────────────────────────────────────────

    @Test
    fun `제출하면 VERIFIED 와 시각을 돌려준다`() {
        stubSubmit(
            Verification(
                id = 5L,
                challengeId = 7L,
                userId = meId,
                status = VerificationStatus.VERIFIED,
                photoUrl = "7/1/9f3c.jpg",
                verifiedAt = LocalDateTime.of(2026, 8, 14, 15, 32, 10),
                createdAt = LocalDateTime.of(2026, 8, 14, 9, 5),
            ),
        )

        mockMvc.perform(multipart("/api/v1/challenges/7/verification").file(jpegPart))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.challengeId").value(7))
            .andExpect(jsonPath("$.data.status").value("VERIFIED"))
            // ADR-0010: `T` 구분자나 `Z` suffix 가 섞이면 모바일 파서가 거부한다.
            .andExpect(jsonPath("$.data.verifiedAt").value("2026-08-14 15:32:10"))
    }

    /** 🔴 응답에 사진 **key** 를 되돌려주지 않는다 — 앱은 key 를 볼 일이 없고, 새는 면만 넓힌다. */
    @Test
    fun `제출 응답에 photoKey 를 싣지 않는다`() {
        stubSubmit(
            Verification(5L, 7L, meId, VerificationStatus.VERIFIED, "7/1/9f3c.jpg", LocalDateTime.now(), LocalDateTime.now()),
        )

        val body = mockMvc.perform(multipart("/api/v1/challenges/7/verification").file(jpegPart))
            .andReturn().response.contentAsString

        assertTrue(!body.contains("photoKey"), "actual=$body")
    }

    /**
     * 🔴 part 이름은 `photo` 여야 한다 (계약 §3). 다른 이름으로 오면 `@RequestPart` 바인딩이
     * 컨트롤러 진입 전에 `MissingServletRequestPartException` 을 던지고, `GlobalExceptionHandler`
     * 가 700 으로 바꾼다 — 500 이 새 나가면 앱이 사용자에게 안내할 수 없다.
     */
    @Test
    fun `part 이름이 photo 가 아니면 code 700 이 나간다`() {
        val wrongName = MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegPart.bytes)

        mockMvc.perform(multipart("/api/v1/challenges/7/verification").file(wrongName))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value("사진을 첨부해주세요"))
    }

    /**
     * 🔴 *"이미 인증했으면 700"* 테스트는 **삭제됐다** (verification-photo-replace, 2026-09-02).
     * 재제출이 `last-write-wins` 로 바뀌었으므로 그 테스트는 이제 **틀린 것을 지킨다.**
     * 교체 동작 자체는 `VerificationServiceTest` 가 덮는다 — 여기 wire 모양은 첫 제출과 같다.
     */
    @Test
    fun `교체 제출도 첫 제출과 같은 모양으로 나간다`() {
        stubSubmit(
            Verification(
                id = 5L,
                challengeId = 7L,
                userId = meId,
                status = VerificationStatus.VERIFIED,
                photoUrl = "7/1/beef.jpg",
                verifiedAt = LocalDateTime.of(2026, 8, 14, 18, 4, 2),
                createdAt = LocalDateTime.of(2026, 8, 14, 9, 5),
            ),
        )

        mockMvc.perform(multipart("/api/v1/challenges/7/verification").file(jpegPart))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SUCCESS))
            .andExpect(jsonPath("$.data.status").value("VERIFIED"))
            // 🔴 verifiedAt 은 최종 교체 시각이다.
            .andExpect(jsonPath("$.data.verifiedAt").value("2026-08-14 18:04:02"))
    }

    @Test
    fun `IN_PROGRESS 가 아니면 HTTP 200 에 code 705 로 나간다`() {
        stubSubmitThrows(OneButtonDialogException(VerificationService.MSG_NOT_IN_PROGRESS))

        mockMvc.perform(multipart("/api/v1/challenges/7/verification").file(jpegPart))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.error").value(true))
            .andExpect(jsonPath("$.code").value(ResponseCode.ONE_BUTTON_DIALOG_ERROR))
            .andExpect(jsonPath("$.message").value(VerificationService.MSG_NOT_IN_PROGRESS))
    }

    // ─── §4 조회 ─────────────────────────────────────────────────

    @Test
    fun `양측을 역할 그대로 내려주고 VERIFIED 인 쪽만 상대 경로를 갖는다`() {
        Mockito.`when`(verificationService.getVerifications(anyLong(), anyLong())).thenReturn(
            VerificationsView(
                challengeId = 7L,
                challenger = PartyVerificationView(
                    userId = 1L,
                    status = VerificationStatus.VERIFIED,
                    photoUrl = "/api/v1/challenges/7/photos/challenger",
                    verifiedAt = LocalDateTime.of(2026, 8, 14, 15, 32, 10),
                    photoDeleted = false,
                    aiReason = null,
                ),
                opponent = PartyVerificationView(
                    userId = 14L,
                    status = VerificationStatus.PENDING,
                    photoUrl = null,
                    verifiedAt = null,
                    photoDeleted = false,
                    aiReason = null,
                ),
            ),
        )

        mockMvc.perform(get("/api/v1/challenges/7/verifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.challengeId").value(7))
            .andExpect(jsonPath("$.data.challenger.userId").value(1))
            .andExpect(jsonPath("$.data.challenger.status").value("VERIFIED"))
            // 🔴 만료 없는 **상대 경로** 그대로 나간다 — 앱이 자기 baseUrl 에 붙인다.
            .andExpect(jsonPath("$.data.challenger.photoUrl").value("/api/v1/challenges/7/photos/challenger"))
            .andExpect(jsonPath("$.data.challenger.verifiedAt").value("2026-08-14 15:32:10"))
            .andExpect(jsonPath("$.data.opponent.status").value("PENDING"))
    }

    /**
     * 🔴 아직 인증 안 한 쪽의 두 필드는 **키가 남은 채 null** 이어야 한다.
     *
     * `jsonPath(...).doesNotExist()` 로는 *"키가 없다"* 와 *"값이 null"* 을 구별하지 못한다 —
     * 모바일에서는 서로 다른 예외가 되는 구분이라 원문 문자열로 확인한다 (#24 선례).
     */
    @Test
    fun `PENDING 인 쪽도 photoUrl 키가 null 로 남는다`() {
        Mockito.`when`(verificationService.getVerifications(anyLong(), anyLong())).thenReturn(
            VerificationsView(
                challengeId = 7L,
                challenger = PartyVerificationView(1L, VerificationStatus.PENDING, null, null, photoDeleted = false, aiReason = null),
                opponent = PartyVerificationView(14L, VerificationStatus.PENDING, null, null, photoDeleted = false, aiReason = null),
            ),
        )

        val body = mockMvc.perform(get("/api/v1/challenges/7/verifications"))
            .andReturn().response.contentAsString

        assertTrue(body.contains(""""photoUrl":null"""), "actual=$body")
        assertTrue(body.contains(""""verifiedAt":null"""), "actual=$body")
        // 사진이 없는 것과 **탈퇴로 지워진 것**은 다르다 — 아직 안 올린 쪽은 false 다.
        assertTrue(body.contains(""""photoDeleted":false"""), "actual=$body")
    }

    /**
     * 🔴 탈퇴로 지워진 사진은 `photoUrl = null` 인 채 `photoDeleted = true` 로 나간다
     * (mypage T-B1 부록 A).
     *
     * 앱은 `photoUrl == null` 을 이미 `"사진을 불러오지 못했어요"` 로 그리고 있다 — 그 문구를
     * 탈퇴자가 빌려 쓰면 **영구 상태를 일시적 실패의 어휘로** 말하게 되고, 더 나쁘게는
     * **진짜 데이터 이상이 탈퇴자 뒤에 숨는다.** 두 상태가 wire 에서 갈리는 것을 고정한다.
     */
    @Test
    fun `탈퇴로 지워진 사진은 photoDeleted true 로 나간다`() {
        Mockito.`when`(verificationService.getVerifications(anyLong(), anyLong())).thenReturn(
            VerificationsView(
                challengeId = 7L,
                challenger = PartyVerificationView(
                    userId = 1L,
                    status = VerificationStatus.VERIFIED,
                    photoUrl = null,
                    verifiedAt = LocalDateTime.of(2026, 8, 14, 15, 32, 10),
                    photoDeleted = true,
                    aiReason = null,
                ),
                opponent = PartyVerificationView(14L, VerificationStatus.PENDING, null, null, photoDeleted = false, aiReason = null),
            ),
        )

        val body = mockMvc.perform(get("/api/v1/challenges/7/verifications"))
            .andExpect(status().isOk)
            // 탈퇴는 row 를 보존한다 — status 와 verifiedAt 은 그대로다 (mypage T-B2).
            .andExpect(jsonPath("$.data.challenger.status").value("VERIFIED"))
            .andExpect(jsonPath("$.data.challenger.verifiedAt").value("2026-08-14 15:32:10"))
            .andExpect(jsonPath("$.data.challenger.photoDeleted").value(true))
            .andExpect(jsonPath("$.data.opponent.photoDeleted").value(false))
            .andReturn().response.contentAsString

        // key 부재와 값 null 은 모바일에서 서로 다른 예외라 원문까지 본다 (#24 선례).
        assertTrue(body.contains(""""photoUrl":null"""), "actual=$body")
    }

    /** 🔴 spec 수용 기준: 제3자 요청은 거부되고 **사진 경로가 응답에 없다.** */
    @Test
    fun `제3자 조회는 code 700 이고 응답에 사진 경로가 없다`() {
        Mockito.`when`(verificationService.getVerifications(anyLong(), anyLong()))
            .thenThrow(SnackbarException(VerificationService.MSG_NOT_MINE))

        val body = mockMvc.perform(get("/api/v1/challenges/7/verifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(ResponseCode.SNACKBAR_ERROR))
            .andExpect(jsonPath("$.message").value(VerificationService.MSG_NOT_MINE))
            .andReturn().response.contentAsString

        assertTrue(!body.contains("/photos/"), "거부 응답에 사진 경로가 새면 안 된다: $body")
    }

    // ─── 사진 서빙 (2026-08-18 2차 결정 — 조회 서명 URL 폐기) ────

    private val photoPath = "/api/v1/challenges/7/photos/challenger"
    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x11)
    private val photoETag = "\"3f2a1c0d4e5b6a7c8d9e0f1a2b3c4d5e\""

    /**
     * 🔴 이 경로만 `BaseResponse` 가 아니라 **바이트 + HTTP status** 다.
     *
     * 🔴 **`max-age=86400` 은 폐기됐다** (verification-photo-replace, 2026-09-02). 그 값이 서 있던
     * 근거는 *"사진은 재제출 거부로 불변"* 이었는데 **교체가 열리면서 전제가 사라졌다** —
     * 길게 캐시하면 상대가 사진을 바꿔도 최대 하루 동안 옛 사진이 보인다.
     */
    @Test
    fun `사진을 찾으면 200 과 이미지 바이트를 주고 매번 재검증하게 한다`() {
        stubReadPhoto(PhotoReadResult.Found(jpegBytes, "image/jpeg", photoETag))

        mockMvc.perform(get(photoPath))
            .andExpect(status().isOk)
            .andExpect(content().contentType("image/jpeg"))
            .andExpect(content().bytes(jpegBytes))
            .andExpect(header().string("ETag", photoETag))
            .andExpect(header().string("Cache-Control", allOf(containsString("no-cache"), containsString("private"))))
            // 🔴 max-age 가 다시 들어오면 교체된 사진이 그 기간만큼 안 보인다.
            .andExpect(header().string("Cache-Control", not(containsString("max-age=86400"))))
    }

    /** 안 바뀌었으면 **304 + 바디 0바이트** — 재진입 비용은 그대로 싸다. */
    @Test
    fun `If-None-Match 가 현재 ETag 면 304 에 바디가 없다`() {
        stubReadPhoto(PhotoReadResult.Found(jpegBytes, "image/jpeg", photoETag))

        mockMvc.perform(get(photoPath).header("If-None-Match", photoETag))
            .andExpect(status().isNotModified)
            .andExpect(content().bytes(ByteArray(0)))
            .andExpect(header().string("ETag", photoETag))
    }

    /** 🔴 교체 뒤에는 앱이 들고 있는 옛 ETag 가 더는 맞지 않는다 — **새 바이트가 나가야 한다.** */
    @Test
    fun `If-None-Match 가 옛 ETag 면 새 바이트가 나간다`() {
        stubReadPhoto(PhotoReadResult.Found(jpegBytes, "image/jpeg", photoETag))

        mockMvc.perform(get(photoPath).header("If-None-Match", "\"00000000000000000000000000000000\""))
            .andExpect(status().isOk)
            .andExpect(content().bytes(jpegBytes))
            .andExpect(header().string("ETag", photoETag))
    }

    /** 중간 캐시가 약한 검증자(`W/`)나 목록으로 바꿔 보내도 같은 사진이면 304 다. */
    @Test
    fun `약한 검증자와 목록 형태의 If-None-Match 도 받아들인다`() {
        stubReadPhoto(PhotoReadResult.Found(jpegBytes, "image/jpeg", photoETag))

        mockMvc.perform(get(photoPath).header("If-None-Match", "\"deadbeef\", W/$photoETag"))
            .andExpect(status().isNotModified)
    }

    /** 실패는 **바디 없이 status 로만** 답한다 — JSON 바디가 나가면 앱 이미지 로더가 깨진다. */
    @Test
    fun `당사자가 아니면 403 에 바디가 없다`() {
        stubReadPhoto(PhotoReadResult.Forbidden)

        mockMvc.perform(get(photoPath))
            .andExpect(status().isForbidden)
            .andExpect(content().bytes(ByteArray(0)))
            .andExpect(header().string("Cache-Control", containsString("no-store")))
    }

    /**
     * 🔴 404 는 절대 캐시하면 안 된다 — 같은 URL 이 상대의 인증 뒤에 404 → 200 으로 바뀐다.
     * 캐시되면 상대가 인증한 뒤에도 사진이 안 보인다.
     */
    @Test
    fun `사진이 없으면 404 에 바디가 없고 no-store 다`() {
        stubReadPhoto(PhotoReadResult.NotFound)

        mockMvc.perform(get(photoPath))
            .andExpect(status().isNotFound)
            .andExpect(content().bytes(ByteArray(0)))
            .andExpect(header().string("Cache-Control", containsString("no-store")))
    }

    /**
     * 🔴 party 가 `challenger`/`opponent` 가 아니면 400 — **서비스에 닿기 전에** 끊는다.
     * enum 을 `@PathVariable` 로 직접 받지 않는 이유이기도 하다 (변환 실패가 500 으로 새는 것 방지).
     */
    @Test
    fun `party 가 잘못된 문자열이면 400 이고 서비스가 호출되지 않는다`() {
        mockMvc.perform(get("/api/v1/challenges/7/photos/stranger"))
            .andExpect(status().isBadRequest)

        Mockito.verify(verificationService, Mockito.never()).readPhoto(anyLong(), anyLong(), anyParty())
    }

    /** 실제 프로덕션 동작 — party 매칭은 `ignoreCase = true` 다. 대문자도 통과한다. */
    @Test
    fun `party 는 대소문자를 가리지 않는다`() {
        stubReadPhoto(PhotoReadResult.Found(jpegBytes, "image/jpeg", photoETag))

        mockMvc.perform(get("/api/v1/challenges/7/photos/CHALLENGER"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("image/jpeg"))
    }
}
