package com.lwg.challenge.infra.external.photo

import java.security.SecureRandom

/**
 * 사진 저장 key 의 **모양**을 한 곳에 모은다 (spec T-B1 — *"저장 경로·파일명 규칙을 정하고 근거를 남겨라"*).
 *
 * ```
 * {challengeId}/{userId}/{난수 32 hex}.jpg
 * 예: 7/12/9f3c8a1b2e4d5f60a1b2c3d4e5f60718.jpg
 * ```
 *
 * ## 🔴 사용자 입력이 한 글자도 들어가지 않는다
 *
 * 파일명은 **`SecureRandom` 16바이트의 hex** 이고 앞 두 조각은 서버가 아는 숫자 id 다.
 * 앱이 보내온 문자열이 경로가 되는 지점이 아예 없으므로 `../` 류의 경로 탈출이 **형태상 불가능**하다.
 * spec T-B1 이 못박은 요구다.
 *
 * ## 왜 그냥 난수 한 덩어리가 아닌가
 *
 * 앞에 `challengeId/userId` 를 붙이면 **디렉터리가 갈라져** 한 폴더에 파일이 무한히 쌓이지 않고,
 * 나중에 *"이 챌린지 사진 전부"* 를 지우는 보관 정책(ADR-0011 후속)이 디렉터리 삭제로 끝난다.
 * 두 값은 앱이 이미 아는 정보라 노출에 의미가 없다.
 *
 * ## 왜 난수가 필요한가 — id 조합만으로는 안 된다
 *
 * `{challengeId}/{userId}.jpg` 로 하면 key 가 **예측 가능**해지고, 무엇보다 같은 사용자가
 * 다시 올릴 때 key 가 그대로라 `store` 가 이전 사진을 덮어쓴다. 부를 때마다 달라야
 * `store` 가 항상 빈 자리에 쓴다.
 *
 * (조회는 서명 URL 이 아니라 JWT 로 보호되는 서버 엔드포인트가 담당한다 — key 예측 불가는
 * 이제 1차 방어가 아니라 덤이다. 접근 판정은 `VerificationService.readPhoto` 가 한다.)
 *
 * ## 확장자를 고정하는 이유
 *
 * 앱이 리사이즈해서 올리는 JPEG 만 받는다(spec T-M2, 계약 §2). 확장자를 협상 대상으로 두면
 * **앱이 준 문자열이 파일명에 섞이는 경로**가 다시 생긴다.
 */
object PhotoKeys {

    /** `{숫자}/{숫자}/{32 hex}.jpg` 만 통과. 이 밖의 문자열은 저장소에 닿지 못한다. */
    private val KEY_PATTERN = Regex("""^\d+/\d+/[0-9a-f]{32}\.jpg$""")

    private val RANDOM = SecureRandom()

    /** 새 key 를 만든다. 부를 때마다 다른 값이 나온다. */
    fun newKey(challengeId: Long, userId: Long): String =
        "$challengeId/$userId/${randomHex()}.jpg"

    /**
     * [key] 가 [newKey] 가 만드는 모양인가.
     *
     * ## 🔴 검증은 이것 하나뿐이다 — 던지는 짝을 다시 만들지 마라
     *
     * 저장소로 들어가는 경로(`read`, `delete`)는 이 함수로 **거르기만** 한다.
     * 모양이 아니면 `read` 는 `null`, `delete` 는 no-op 으로 조용히 끝난다(fail-soft).
     * 예전에 있던 `require(key)` — 모양이 아니면 `IllegalArgumentException` 을 던지던 짝 —
     * 은 유일한 호출자(조회 서명 URL 발급)가 사라지면서 **삭제했다**. 되살리지 마라.
     *
     * 이유는 "호출자가 없어서"가 아니라 **던지면 안 되는 경로**이기 때문이다.
     * 사진 서빙은 JSON 이 아니라 **바이트를 그대로 돌려주는 응답**이다. 여기서 예외가 새면
     * `GlobalExceptionHandler` 가 그걸 받아 `Content-Type: application/json` 바디로 바꿔
     * HTTP 200 + 에러 JSON 또는 500 을 내보낸다. 앱 이미지 로더는 그 응답을 이미지로 읽지 못하고 깨진다.
     * 그래서 실패는 예외가 아니라 **결과값**으로 표현한다 —
     * `PhotoReadResult.NotFound` / `Forbidden` (`VerificationService.readPhoto`).
     * 새 진입점을 쓰면서 "안전망" 삼아 던지는 검증을 넣고 싶어지면, 그건 안전망이 아니라
     * 위 회귀를 되살리는 함정이다. 이 함수의 `Boolean` 을 보고 분기하라.
     *
     * 쓰기(`store`)에는 애초에 검증이 필요 없다. key 를 서버가 [newKey] 로 만들기 때문에
     * 사용자 입력이 들어올 자리가 없다.
     */
    fun isValid(key: String): Boolean = KEY_PATTERN.matches(key)

    private fun randomHex(): String {
        val bytes = ByteArray(16)
        RANDOM.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
