package com.lwg.challenge.service.challenge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 계약서 본문 템플릿 (soul-oath, design-bridge 검토 수정 3건).
 *
 * ## 이 테스트가 특별히 중요한 이유
 * 본문은 `contracts.content` 에 **박제**된다. 잘못된 문구가 한 번 나가면 **소급 수정이 안 된다** —
 * 나중에 템플릿을 고쳐도 이미 맺어진 계약서는 그대로다. 그래서 문구 자체를 단언으로 고정한다.
 */
class ContractContentTest {

    private val date = LocalDate.of(2026, 7, 28)

    private fun render(
        challenger: String = "도윤",
        opponent: String = "수아",
        opponentMission: String? = null,
    ) = ContractContent.render(
        challengerNickname = challenger,
        challengerMission = "운동 1시간",
        opponentNickname = opponent,
        opponentMission = opponentMission,
        betContent = "커피 사기",
        challengeDate = date,
    )

    // ─── 1. 조사 ─────────────────────────────────────────────────

    @Test
    fun `조사 병기가 남아 있지 않다`() {
        val content = render()
        // "와(과)" / "은(는)" 은 관공서 양식 문투다. 개인 이름이 들어간 계약서에 남으면 조잡하다.
        assertFalse(content.contains("와(과)"), "조사 병기가 남았다:\n$content")
        assertFalse(content.contains("은(는)"), "조사 병기가 남았다:\n$content")
        // 조사가 띄어져 나오던 문제도 함께 막는다.
        assertFalse(content.contains(" 와 "), "조사가 띄어졌다:\n$content")
        assertFalse(content.contains(" 의 미션"), "조사가 띄어졌다:\n$content")
    }

    @Test
    fun `받침이 있으면 과-은, 없으면 와-는 이다`() {
        // 도윤(받침 ㄴ) → "과" / 수아(받침 없음) → "는"
        assertTrue(render(challenger = "도윤", opponent = "수아").contains("도윤과 수아는"))
        // 뒤집어도 성립해야 한다 — 두 자리가 각각 판정된다.
        assertTrue(render(challenger = "수아", opponent = "도윤").contains("수아와 도윤은"))
    }

    @Test
    fun `판정 불가 닉네임은 받침 없음으로 떨어진다`() {
        // 영문은 표기만으로 발음이 안 정해진다(Kim→과 / Sua→와). 서버가 추정하면 반드시 틀리는
        // 경우가 생기므로 한쪽으로 고정한다 — 병기로 되돌아가지는 않는다.
        for (name in listOf("Kim", "sua", "🔥")) {
            val content = render(challenger = name, opponent = name)
            assertTrue(content.contains("${name}와 ${name}는"), "판정 불가 처리 실패: $name\n$content")
            assertFalse(content.contains("(과)"), "병기가 붙었다: $name")
        }
    }

    @Test
    fun `숫자로 끝나면 한국어 읽기의 받침으로 판정한다`() {
        // 🔴 숫자는 영문과 다르다 — 읽기가 하나로 정해져 있어 추정이 아니다.
        // "테스터3와" 로 나가던 것을 mobile-dev 가 실서버 계약서 본문에서 발견했다 (3=삼, 받침 ㅁ).
        val batchimByName = mapOf(
            "테스터0" to true,  // 영/십
            "테스터1" to true,  // 일
            "테스터2" to false, // 이
            "테스터3" to true,  // 삼
            "테스터4" to false, // 사
            "테스터5" to false, // 오
            "테스터6" to true,  // 육
            "테스터7" to true,  // 칠
            "테스터8" to true,  // 팔
            "테스터9" to false, // 구
        )
        for ((name, batchim) in batchimByName) {
            val content = render(challenger = name, opponent = name)
            val expected = if (batchim) "${name}과 ${name}은" else "${name}와 ${name}는"
            assertTrue(content.contains(expected), "$name → \"$expected\" 여야 한다\n$content")
        }
    }

    @Test
    fun `여러 자리 숫자는 마지막 자리가 읽기를 결정한다`() {
        // 12 → 십'이'(받침 없음) / 13 → 십'삼'(받침 ㅁ) / 20 → 이'십'(받침 ㅂ)
        assertTrue(render(challenger = "우건12", opponent = "우건12").contains("우건12와 우건12는"))
        assertTrue(render(challenger = "우건13", opponent = "우건13").contains("우건13과 우건13은"))
        assertTrue(render(challenger = "우건20", opponent = "우건20").contains("우건20과 우건20은"))
    }

    @Test
    fun `미션 줄의 소유격도 붙여 쓴다`() {
        val content = render(challenger = "도윤", opponent = "수아")
        assertTrue(content.contains("· 도윤의 미션: 운동 1시간"), content)
        assertTrue(content.contains("· 수아의 미션: "), content)
    }

    // ─── 2. 상대 미션 자리 ───────────────────────────────────────

    @Test
    fun `수락 전에는 상대 미션이 자리표시다`() {
        val content = render(opponentMission = null)
        assertTrue(content.contains("· 수아의 미션: ${ContractContent.PENDING_MISSION}"), content)
    }

    @Test
    fun `수락 후에는 실제 미션이 들어간다`() {
        val content = render(opponentMission = "책 30페이지 읽기")
        assertTrue(content.contains("· 수아의 미션: 책 30페이지 읽기"), content)
        assertFalse(
            content.contains(ContractContent.PENDING_MISSION),
            "완성본에 자리표시가 남으면 안 된다:\n$content",
        )
    }

    // ─── 3. 마감 표기 ────────────────────────────────────────────

    @Test
    fun `마감은 challengeDate 의 24시로 적는다`() {
        val content = render()
        // deadline(= 07-29 00:00)을 그대로 포맷하면 "7월 29일"이 되어 화면("7/28 24:00")과
        // **같은 순간을 다른 날짜로** 적게 된다. 사용자는 7/28 자정으로 이해하고 서명했다.
        assertTrue(content.contains("· 마감: 2026년 7월 28일 24:00"), content)
        assertFalse(content.contains("7월 29일"), "마감이 다음 날짜로 적혔다:\n$content")
        assertFalse(content.contains("00:00"), "자정을 하루의 시작으로 적었다:\n$content")
    }

    @Test
    fun `맹세 날짜와 마감 날짜가 같은 날이다`() {
        // 계약서 안에서 두 날짜가 갈리면 "어? 내일 마감이었나?" 가 된다.
        val content = render()
        assertEquals(
            2,
            Regex("2026년 7월 28일").findAll(content).count(),
            "머리말 날짜와 마감 날짜가 같아야 한다:\n$content",
        )
    }

    // ─── 톤 (의도적으로 유지) ────────────────────────────────────

    @Test
    fun `격식체 마무리 문구를 유지한다`() {
        // 화면은 반말인데 계약서만 격식체인 건 어긋난 게 아니라 의도다 —
        // "화면은 앱이 나에게 말을 거는 것이고, 계약서는 문서다" (pm-lead / design-bridge).
        assertTrue(render().endsWith("서명한 맹세는 무를 수 없다."))
    }
}
