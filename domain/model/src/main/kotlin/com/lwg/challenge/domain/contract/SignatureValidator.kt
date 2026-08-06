package com.lwg.challenge.domain.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lwg.challenge.domain.common.exception.SnackbarException

/**
 * 서명 데이터 규약 상수 (soul-oath api-contract §4.3).
 *
 * ## 모바일과 값이 같아야 한다
 * 모바일도 `SignatureLimits`(`:domain:model`)에 같은 값을 둔다. 어긋나면
 * **클라 통과 → 서버 거부** 가 생긴다 (challenge-create 의 100자 상한을 양측 동일 카운팅으로
 * 맞춘 것과 같은 이유).
 *
 * ## `MAX_POINTS` / `MAX_STROKES` 를 서버가 갖지 않는 이유
 * 그건 **캡처 상한이라 모바일 소유**다. 서버가 같은 숫자를 중복 보유하면 한쪽만 바뀌었을 때
 * 조용히 어긋난다. 서버는 **길이 하나로** 막는다 — 모바일 실측상 최악값이 20,150자이므로
 * 32KB 면 캡처 상한을 넘는 입력을 충분히 걸러낸다.
 */
object SignatureLimits {

    /** 직렬화 문자열 최대 길이. 모바일 실측 최악값 20,150자 + 여유 (api-contract §4.3). */
    const val MAX_SIGNATURE_LENGTH = 32 * 1024

    /** 지원하는 포맷 버전. 이 값만 받는다 — 아래 [SignatureValidator] 주석 참조. */
    const val FORMAT_VERSION = 1
}

/**
 * 서명 벡터 데이터 검증 (soul-oath api-contract §4.4).
 *
 * ## 포맷
 * ```json
 * {"v":1,"g":1000,"s":[[123,456,130,460],[700,200,705,210]]}
 * ```
 * `v`=포맷 버전 / `g`=그리드 해상도 / `s`=획 배열(각 획은 `x,y` 가 번갈아 나오는 **평탄 Int 배열**).
 *
 * ## 서버는 서명을 렌더하지 않는다
 * 구조 검증만 하고 **문자열 그대로 저장**한다. 렌더는 전적으로 모바일 몫이다.
 *
 * ## 🔴 검증은 **쓰기 시점에만** 한다
 * 읽기 경로에서는 크기를 검사하지 않는다. 상한은 **입력 규칙**이지 이미 저장된 데이터를 못 읽게
 * 만들 이유가 아니다 — 나중에 상한을 낮추면 **기존 계약서가 통째로 안 열리게 된다**
 * (mobile-dev 의 디코드 정책과 대칭. api-contract §4.6).
 */
object SignatureValidator {

    private val mapper = ObjectMapper()

    /**
     * 검증 순서가 의미를 만든다:
     *
     * 1. **길이** — 파싱 전에 먼저 본다. 32KB 짜리를 파싱한 뒤 "너무 크다" 고 하는 건 순서가 거꾸로다.
     *    악의적 대용량을 **파싱 비용을 치르기 전에** 막는다.
     * 2. **파싱 + 버전 + 그리드 + 짝수 길이**
     * 3. **빈 서명**
     *
     * @throws SnackbarException 사용자에게 그대로 보이는 한국어 문구 (code 700)
     */
    fun validate(raw: String) {
        if (raw.length > SignatureLimits.MAX_SIGNATURE_LENGTH) {
            throw SnackbarException(MSG_TOO_LARGE)
        }

        val root: JsonNode = runCatching { mapper.readTree(raw) }.getOrNull()
            ?: throw SnackbarException(MSG_MALFORMED)

        // 🔴 v 를 고정하는 이유: 아래 구조 검증(평탄 배열·짝수 길이)은 **v1 전용 규칙**이다.
        // 고정하지 않으면 v2 데이터에 v1 규칙을 조용히 적용하게 된다.
        // 버전 상향은 양측 동시 배포가 필요하다는 뜻이고, 그게 의도다.
        if (!root.path("v").isInt || root.path("v").asInt() != SignatureLimits.FORMAT_VERSION) {
            throw SnackbarException(MSG_MALFORMED)
        }
        if (!root.path("g").isInt || root.path("g").asInt() <= 0) {
            throw SnackbarException(MSG_MALFORMED)
        }

        val strokes = root.path("s")
        if (!strokes.isArray) throw SnackbarException(MSG_MALFORMED)

        var hasAnyPoint = false
        for (stroke in strokes) {
            if (!stroke.isArray) throw SnackbarException(MSG_MALFORMED)
            // 평탄 배열이므로 길이가 홀수면 마지막 좌표에 짝이 없다 — 렌더가 깨진다.
            if (stroke.size() % 2 != 0) throw SnackbarException(MSG_MALFORMED)
            if (stroke.any { !it.isInt }) throw SnackbarException(MSG_MALFORMED)
            if (stroke.size() >= 2) hasAnyPoint = true
        }

        // 빈 서명 = 획이 없거나 모든 획의 점이 0개.
        // ⚠️ 점 1개(탭 한 번)는 **유효**로 본다 — 최소 점 개수를 서버가 발명하면 짧게 서명하는
        //    사람을 오거부하고 그 임계값에 근거가 없다. 모바일 CTA 활성 조건도 같은 규칙(≥1점)이다.
        if (!hasAnyPoint) throw SnackbarException(MSG_EMPTY)
    }

    // ── 사용자 노출 문구 (모바일이 message 를 그대로 화면에 띄운다) ──
    const val MSG_EMPTY = "서명을 해주세요"
    const val MSG_TOO_LARGE = "서명 데이터가 너무 큽니다"
    const val MSG_MALFORMED = "서명 형식이 올바르지 않습니다"
}
