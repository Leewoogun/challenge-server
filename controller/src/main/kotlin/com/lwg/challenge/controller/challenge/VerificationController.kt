package com.lwg.challenge.controller.challenge

import com.lwg.challenge.controller.challenge.dto.ChallengeVerificationsData
import com.lwg.challenge.controller.challenge.dto.ChallengeVerificationsResponse
import com.lwg.challenge.controller.challenge.dto.PartyVerificationDto
import com.lwg.challenge.controller.challenge.dto.SubmitVerificationData
import com.lwg.challenge.controller.challenge.dto.SubmitVerificationResponse
import com.lwg.challenge.domain.common.exception.UnauthorizedException
import com.lwg.challenge.service.verification.PartyVerificationView
import com.lwg.challenge.service.verification.PhotoReadResult
import com.lwg.challenge.service.verification.VerificationParty
import com.lwg.challenge.service.verification.VerificationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * 챌린지 인증 — 사진 제출 / 조회 / 사진 서빙 (challenge-verification api-contract §2~4).
 *
 * ## 🔴 업로드가 이 컨트롤러로 들어온다 (ADR-0011 §3 개정)
 *
 * 앱은 제출 API 에 사진을 **multipart 로 직접** 올리고 서버가 저장까지 한 번에 처리한다.
 * URL 발급 3단계는 2026-08-18 결정으로 폐기됐다 — 릴리즈 전이라 호환 제약이 없고 현재
 * 규모에서 이득이 0 이었다.
 *
 * ## 🔴 사진 서빙도 이 컨트롤러다 — 조회 서명 URL 폐기 (2026-08-18 2차 결정)
 *
 * 예전에는 조회만 서명된 URL(비인증 `/api/v1/photos/...`)로 나갔다. 폐기 근거였던
 * *"이미지 로더에 인증 헤더를 심는 복잡도"* 가 성립하지 않는 것으로 확인되어(Coil 3
 * `coil-network-ktor3` 가 앱의 기존 인증 `HttpClient` 를 그대로 받는다) 서명 체계를 걷어냈다.
 * [readPhoto] 가 **JWT 로 당사자를 요청 시점에 검사**하고 바이트를 돌려준다.
 *
 * ## 🔴 조회를 `GET /challenges/{id}` 에 얹지 않았다
 *
 * 계약 §4 에서 mobile-dev 와 합의한 결정이다. 근거 둘:
 * 1. `GET /challenges/{id}` 는 `PENDING`·계약서 화면에서도 쓰인다. 그쪽엔 인증이 존재하지 않는다.
 * 2. 그 계약은 `confirmed` 다. shape 을 건드리지 않으므로 `change-log.md` 등재가 불필요하다.
 *
 * 인증은 `SecurityFilterChain` + `JwtAuthenticationFilter` 담당. principal 은 userId(Long).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Verification", description = "챌린지 인증 (사진 제출 / 조회 / 사진 서빙)")
class VerificationController(
    private val verificationService: VerificationService,
) {

    @PostMapping(
        "/challenges/{id}/verification",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
    )
    @Operation(
        summary = "인증 사진 제출/교체 (저장 + VERIFIED 전이 + 최초 1회 상대에게 알림)",
        description = "multipart part `photo` 에 JPEG 바이트를 실어 보낸다 (최대 5MB). " +
            "IN_PROGRESS + 당사자만. 🔴 재제출은 **last-write-wins** — 마지막에 올린 사진이 최종본이고 " +
            "이전 사진 파일은 삭제된다. verifiedAt 은 최종 교체 시각으로 갱신되며, " +
            "상대에게 가는 알림은 **최초 인증 1회뿐**이다(교체는 알리지 않는다). " +
            "응답을 유실했으면 그냥 다시 올리면 된다 — 선행 status 조회가 필요 없다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun submit(
        @PathVariable("id") id: Long,
        @RequestPart("photo") photo: MultipartFile,
    ): SubmitVerificationResponse {
        val submitted = verificationService.submit(
            me = currentUserId(),
            challengeId = id,
            photo = photo.bytes,
        )
        return SubmitVerificationResponse(
            data = SubmitVerificationData(
                challengeId = submitted.challengeId,
                status = submitted.status,
                verifiedAt = submitted.verifiedAt,
            ),
        )
    }

    @GetMapping("/challenges/{id}/verifications")
    @Operation(
        summary = "양측 인증 현황 + 사진 경로",
        description = "당사자만 조회 가능 (ADR-0011 §4). challenger/opponent 를 역할 그대로 반환한다. " +
            "photoUrl 은 만료 없는 상대 경로이며, 열 때 Authorization 이 필요하다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun getVerifications(
        @PathVariable("id") id: Long,
    ): ChallengeVerificationsResponse {
        val view = verificationService.getVerifications(me = currentUserId(), challengeId = id)
        return ChallengeVerificationsResponse(
            data = ChallengeVerificationsData(
                challengeId = view.challengeId,
                challenger = view.challenger.toDto(),
                opponent = view.opponent.toDto(),
            ),
        )
    }

    /**
     * 인증 사진 바이트 서빙 (2026-08-18 2차 결정 — 조회 서명 URL 폐기).
     *
     * ## 🔴 이 경로만 `BaseResponse` 를 쓰지 않는다 — 바이트가 응답이다
     *
     * 앱 이미지 로더가 이 응답을 그대로 이미지로 파싱한다. 실패를 JSON 바디로 답하면
     * 로더가 깨지므로 **HTTP status 로만** 답한다. 서비스도 같은 이유로 예외 대신
     * [PhotoReadResult] 를 돌려준다 — `VerificationService.readPhoto` KDoc 참조.
     *
     * ## 🔴 캐시 — 200 은 **매번 재검증**, 404 는 절대 캐시하지 않는다
     *
     * 🔴 **`max-age=86400` 은 폐기됐다 (verification-photo-replace, 2026-09-02).** 그 값이 서 있던
     * 근거는 *"사진은 재제출 거부로 불변"* 이었는데, **교체가 열리면서 그 전제가 사라졌다.**
     * 경로는 그대로인데 바이트가 바뀌므로, 길게 캐시하면 **상대가 사진을 바꿔도 최대 하루 동안
     * 옛 사진이 보인다** — 에러도 로그도 남지 않는 조용한 실패다.
     *
     * 대신 `no-cache`(= 쓸 때마다 재검증) + **ETag** 를 준다. 바뀌지 않았으면 **304 + 바디 0바이트**
     * 라 재진입 비용은 그대로 싸다. 🔵 ETag 는 저장소 key 에서 나오므로 *"사진이 바뀌면 반드시
     * 바뀐다"* 가 보장된다 (`VerificationService.photoETag`).
     *
     * ⚠️ **HTTP 캐시만 다룬다.** 이미지 로더가 URL 을 키로 하는 **메모리 캐시**를 따로 두면
     * 이 헤더가 닿지 않는다 — 앱 쪽에서 별도로 무효화해야 한다 (mobile-dev 통지 대상).
     *
     * **404 는 캐시하면 안 된다** — 상대가 아직 인증하지 않은 상태에서 받은 404 가 캐시되면
     * **상대가 인증한 뒤에도 사진이 안 보인다.** 같은 URL 의 응답이 404 → 200 으로 바뀌는
     * 자리다. 403/404 에 `no-store` 를 붙이는 이유다.
     */
    @GetMapping("/challenges/{id}/photos/{party}")
    @Operation(
        summary = "인증 사진 바이트 (당사자만)",
        description = "🔴 응답이 BaseResponse 가 아니라 **JPEG 바이트**다. 실패는 바디 없이 HTTP status 로만 답한다: " +
            "200=바이트(image/jpeg), 304=If-None-Match 가 현재 ETag 와 같음, " +
            "400=party 가 challenger/opponent 가 아님, 401=인증 없음, " +
            "403=당사자 아님, 404=챌린지 없음/아직 인증 안 함/파일 없음. " +
            "앱은 이미지 로더의 인증 HttpClient 로 이 경로를 그대로 연다. " +
            "🔴 사진 교체가 가능하므로 같은 경로가 다른 바이트를 낼 수 있다 — 200 은 `no-cache` 이며 ETag 로 재검증한다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    fun readPhoto(
        @PathVariable("id") id: Long,
        @PathVariable("party") party: String,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<ByteArray> {
        // 🔴 enum 을 @PathVariable 로 직접 받지 않는다 — 변환 실패가
        //    MethodArgumentTypeMismatchException 으로 튀어 handleUncaught 의 500 이 된다.
        val verificationParty = VerificationParty.entries
            .firstOrNull { it.name.equals(party, ignoreCase = true) }
            ?: return ResponseEntity.badRequest().build()

        return when (val result = verificationService.readPhoto(currentUserId(), id, verificationParty)) {
            is PhotoReadResult.Found ->
                if (matchesETag(ifNoneMatch, result.etag)) {
                    ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                        .eTag(result.etag)
                        .cacheControl(revalidate())
                        .build()
                } else {
                    ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(result.contentType))
                        .eTag(result.etag)
                        .cacheControl(revalidate())
                        .body(result.bytes)
                }

            PhotoReadResult.Forbidden -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .build()

            PhotoReadResult.NotFound -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .build()
        }
    }

    /**
     * 사진 응답의 캐시 정책 — **저장은 하되 쓸 때마다 재검증**한다 (`no-cache, private`).
     *
     * `no-store` 가 아니다. 바이트를 갖고 있어야 304 가 의미를 갖는다.
     */
    private fun revalidate(): CacheControl = CacheControl.noCache().cachePrivate()

    /**
     * `If-None-Match` 가 현재 ETag 를 가리키는가.
     *
     * 목록(`a, b`)과 약한 검증자(`W/"..."`)를 받아 준다 — 중간 캐시가 붙이는 형태다.
     * `*` 는 **조건부 GET 의미가 아니라 조건부 쓰기용**이라 여기서 다루지 않는다.
     */
    private fun matchesETag(ifNoneMatch: String?, current: String): Boolean =
        ifNoneMatch
            ?.split(',')
            ?.any { it.trim().removePrefix("W/") == current }
            ?: false

    // ─── helpers ─────────────────────────────────────────────────

    private fun PartyVerificationView.toDto() = PartyVerificationDto(
        userId = userId,
        status = status,
        photoUrl = photoUrl,
        verifiedAt = verifiedAt,
        // 탈퇴로 사진이 지워졌는지는 서비스가 이미 판정했다 — 앱은 "왜" 가 아니라
        // "뭘 그릴지" 만 받는다 (`PartyVerificationDto.photoDeleted` KDoc).
        photoDeleted = photoDeleted,
        // 통과했거나 판별하지 않았으면 null 이다 — 앱은 non-null 일 때만 그린다.
        aiReason = aiReason,
    )

    /** `ChallengeCommandController` 와 같은 규칙. principal 은 userId(Long). */
    private fun currentUserId(): Long {
        val auth = SecurityContextHolder.getContext().authentication
            ?: throw UnauthorizedException("인증이 필요합니다")
        return when (val principal = auth.principal) {
            is Long -> principal
            is Number -> principal.toLong()
            is String -> principal.toLongOrNull()
                ?: throw UnauthorizedException("인증 정보가 올바르지 않습니다")
            else -> throw UnauthorizedException("인증 정보가 올바르지 않습니다")
        }
    }
}
