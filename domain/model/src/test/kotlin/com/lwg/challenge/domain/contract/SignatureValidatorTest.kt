package com.lwg.challenge.domain.contract

import com.lwg.challenge.domain.common.ResponseCode
import com.lwg.challenge.domain.common.exception.BusinessException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * 서명 검증 (soul-oath T-B4).
 *
 * 포맷: `{"v":1,"g":1000,"s":[[x,y,x,y,...]]}` — 각 획은 평탄 Int 배열이라 **길이가 항상 짝수**다.
 */
class SignatureValidatorTest {

    private fun sig(strokes: String, v: Int = 1, g: Int = 1000) = """{"v":$v,"g":$g,"s":$strokes}"""

    // ─── 유효 ────────────────────────────────────────────────────

    @Test
    fun `획 1개 점 2개는 유효하다`() {
        assertDoesNotThrow { SignatureValidator.validate(sig("[[100,200,110,210]]")) }
    }

    @Test
    fun `점 1개짜리 획도 유효하다`() {
        // 최소 점 개수를 서버가 발명하지 않는다 — 짧게 서명하는 사람을 오거부하게 되고
        // 임계값(5? 10?)에 근거가 없다. 모바일 CTA 활성 조건도 같은 규칙(>=1점)이다.
        assertDoesNotThrow { SignatureValidator.validate(sig("[[500,500]]")) }
    }

    @Test
    fun `빈 획이 섞여 있어도 점이 하나라도 있으면 유효하다`() {
        assertDoesNotThrow { SignatureValidator.validate(sig("[[],[1,2],[]]")) }
    }

    @Test
    fun `경계값 좌표 0 과 g 를 허용한다`() {
        assertDoesNotThrow { SignatureValidator.validate(sig("[[0,0,1000,1000]]")) }
    }

    // ─── 빈 서명 ─────────────────────────────────────────────────

    @Test
    fun `획이 없으면 빈 서명이다`() {
        val e = assertThrows<BusinessException> { SignatureValidator.validate(sig("[]")) }
        assertEquals(ResponseCode.SNACKBAR_ERROR, e.code)
        assertEquals(SignatureValidator.MSG_EMPTY, e.message)
    }

    @Test
    fun `모든 획의 점이 0개면 빈 서명이다`() {
        val e = assertThrows<BusinessException> { SignatureValidator.validate(sig("[[],[],[]]")) }
        assertEquals(SignatureValidator.MSG_EMPTY, e.message)
    }

    // ─── 크기 상한 ───────────────────────────────────────────────

    @Test
    fun `32KB 를 넘으면 거부된다`() {
        val huge = sig("[[" + "1,2,".repeat(20_000).trimEnd(',') + "]]")
        assert(huge.length > SignatureLimits.MAX_SIGNATURE_LENGTH)

        val e = assertThrows<BusinessException> { SignatureValidator.validate(huge) }
        assertEquals(SignatureValidator.MSG_TOO_LARGE, e.message)
    }

    @Test
    fun `길이 검사가 파싱보다 먼저다`() {
        // 상한을 넘으면서 **동시에 깨진 JSON** 인 입력. 길이 메시지가 나와야 한다 —
        // 32KB 짜리를 파싱한 뒤 "너무 크다" 고 하는 건 순서가 거꾸로다.
        val hugeAndBroken = "{" + "x".repeat(SignatureLimits.MAX_SIGNATURE_LENGTH + 10)

        val e = assertThrows<BusinessException> { SignatureValidator.validate(hugeAndBroken) }
        assertEquals(SignatureValidator.MSG_TOO_LARGE, e.message)
    }

    // ─── 구조 ────────────────────────────────────────────────────

    @Test
    fun `홀수 길이 획은 거부된다`() {
        // 평탄 배열이라 홀수면 마지막 좌표에 짝이 없다 — 렌더가 깨진다.
        val e = assertThrows<BusinessException> { SignatureValidator.validate(sig("[[1,2,3]]")) }
        assertEquals(SignatureValidator.MSG_MALFORMED, e.message)
    }

    @Test
    fun `알 수 없는 버전은 거부된다`() {
        // 구조 검증(평탄 배열·짝수 길이)이 v1 전용 규칙이라, 고정하지 않으면
        // v2 데이터에 v1 규칙을 조용히 적용하게 된다.
        for (v in listOf(0, 2, 99)) {
            val e = assertThrows<BusinessException> { SignatureValidator.validate(sig("[[1,2]]", v = v)) }
            assertEquals(SignatureValidator.MSG_MALFORMED, e.message, "v=$v")
        }
    }

    @Test
    fun `g 가 0 이하면 거부된다`() {
        for (g in listOf(0, -1)) {
            val e = assertThrows<BusinessException> { SignatureValidator.validate(sig("[[1,2]]", g = g)) }
            assertEquals(SignatureValidator.MSG_MALFORMED, e.message, "g=$g")
        }
    }

    @Test
    fun `필수 필드가 없으면 거부된다`() {
        for (bad in listOf(
            """{"g":1000,"s":[[1,2]]}""",   // v 없음
            """{"v":1,"s":[[1,2]]}""",      // g 없음
            """{"v":1,"g":1000}""",         // s 없음
        )) {
            val e = assertThrows<BusinessException> { SignatureValidator.validate(bad) }
            assertEquals(SignatureValidator.MSG_MALFORMED, e.message, bad)
        }
    }

    @Test
    fun `깨진 JSON 이나 잘못된 타입은 거부된다`() {
        for (bad in listOf(
            "not json",
            "",
            """{"v":1,"g":1000,"s":"not-an-array"}""",
            """{"v":1,"g":1000,"s":[[1,"x"]]}""",     // 좌표가 Int 가 아님
            """{"v":1,"g":1000,"s":[123]}""",         // 획이 배열이 아님
            """{"v":"1","g":1000,"s":[[1,2]]}""",     // v 가 문자열
        )) {
            val e = assertThrows<BusinessException> { SignatureValidator.validate(bad) }
            assertEquals(SignatureValidator.MSG_MALFORMED, e.message, bad)
        }
    }

    // ─── 상수 ────────────────────────────────────────────────────

    @Test
    fun `상수가 계약과 일치한다`() {
        // 모바일 SignatureLimits 와 같은 값이어야 한다 — 어긋나면 클라 통과 → 서버 거부가 생긴다.
        assertEquals(32 * 1024, SignatureLimits.MAX_SIGNATURE_LENGTH)
        assertEquals(1, SignatureLimits.FORMAT_VERSION)
    }

    // ─── 최악값 대조 ─────────────────────────────────────────────

    /**
     * 모바일 캡처 상한(`MAX_POINTS` 2,000 / `MAX_STROKES` 64)에서 나올 수 있는 **가장 긴 서명**을
     * 서버가 **독립적으로 다시 만들어** 길이를 잰다.
     *
     * ## 왜 숫자를 하드코딩해 대조하는가
     * mobile-dev 가 실측한 값은 **20,150자**다. 서버가 같은 규칙으로 만든 최악값이 같은 숫자가
     * 나와야 "32KB 상한이 캡처 상한을 덮는다" 는 계약 §4.3 의 근거가 **양쪽에서 성립**한다.
     * 한쪽만 재면 인코딩 가정(구분자·자릿수)이 어긋나도 아무도 모른다.
     *
     * 최악값 = 모든 좌표가 4자리(`1000`) + 획을 64개로 쪼개 대괄호·쉼표를 최대로 만든 경우.
     */
    @Test
    fun `모바일 캡처 상한의 최악값이 서버 상한 안에 들어온다`() {
        val maxPoints = 2_000
        val maxStrokes = 64

        // 2,000 점을 64 획에 최대한 고르게 나눈다 (16 획 x 32점 + 48 획 x 31점 = 2,000).
        val pointsPerStroke = List(maxStrokes) { i ->
            maxPoints / maxStrokes + if (i < maxPoints % maxStrokes) 1 else 0
        }
        assertEquals(maxPoints, pointsPerStroke.sum())

        // 좌표는 전부 g(=1000) — 4자리라 인코딩이 가장 길어진다.
        val strokes = pointsPerStroke.joinToString(",", "[", "]") { n ->
            List(n) { "1000,1000" }.joinToString(",", "[", "]")
        }
        val worst = sig(strokes)

        // 🔴 mobile-dev 실측값과 정확히 일치해야 한다 (api-contract §4.3).
        assertEquals(20_150, worst.length, "최악값 인코딩 길이가 모바일 실측(20,150자)과 다르다")
        assertTrue(
            worst.length < SignatureLimits.MAX_SIGNATURE_LENGTH,
            "캡처 상한의 최악값이 서버 상한을 넘으면 정상 서명이 거부된다",
        )

        // 그리고 그 최악값이 실제로 통과해야 한다 — 길이만 재고 검증을 안 돌리면 반쪽이다.
        assertDoesNotThrow { SignatureValidator.validate(worst) }
    }
}
