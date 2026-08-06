package com.lwg.challenge.service.challenge

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 계약서 본문 템플릿 (soul-oath).
 *
 * **사용자 커스터마이즈는 비범위** — 서버가 정해진 문구로 채운다(spec).
 *
 * ## 🔴 언제 렌더하고 언제 굳는가 — **두 번 렌더한 뒤 굳는다**
 *
 * ```
 * 신청       → render(opponentMission = null)   → "(수락 시 기재)"
 * 수락+서명  → render(opponentMission = 실제값)  → 완성본으로 덮어씀
 * 그 이후    → 절대 다시 렌더하지 않는다
 * ```
 *
 * 신청 시점에 한 번만 굳히면 **상대 미션 자리가 영구히 `(수락 시 기재)` 로 남는다** —
 * 상대 미션은 수락 시점에야 정해지기 때문이다. 계약서의 존재 이유가 "양측의 약속" 인데
 * **한쪽이 비어 있는 문서가 완성본으로 남는다.**
 *
 * **박제 원칙과 충돌하지 않는다.** 박제의 취지는 *"나중에 템플릿을 바꿔도 이미 맺은 맹세가
 * 소급해 바뀌면 안 된다"* 이지 *"미완성 스냅샷을 영원히 남기자"* 가 아니다.
 * **박제 대상은 완성된 약속이어야 하고, 약속이 완성되는 시점이 `is_finalized` 다.**
 *
 * 그래서 `is_finalized = true` 이후에는 이 함수가 다시 불리지 않는다. 그 시점 이후로는
 * 템플릿 문구를 바꿔도 **이미 맺어진 계약서 본문은 변하지 않는다.**
 *
 * ## ⚠️ 문구는 소급 수정이 안 된다
 * 본문이 DB 에 박제되므로 한 번 나간 문구는 나중에 고쳐도 **기존 계약서에는 반영되지 않는다.**
 */
object ContractContent {

    private val DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일")

    /** 상대가 아직 수락하지 않은 구간에 들어가는 자리표시. */
    const val PENDING_MISSION = "(수락 시 기재)"

    /**
     * @param opponentMission 상대 미션. **null 이면 아직 수락 전**이라 [PENDING_MISSION] 이 들어간다.
     * @param challengeDate 챌린지 날짜. 마감은 이 날짜의 `24:00` 으로 적는다(아래 "마감 표기" 참조).
     */
    fun render(
        challengerNickname: String,
        challengerMission: String,
        opponentNickname: String,
        opponentMission: String?,
        betContent: String,
        challengeDate: LocalDate,
    ): String {
        val challengerSubject = challengerNickname + wa(challengerNickname)
        val opponentSubject = opponentNickname + neun(opponentNickname)
        val date = challengeDate.format(DATE)

        return """
            영혼의 맹세

            $date, $challengerSubject $opponentSubject
            아래의 약속을 걸고 맹세한다.

            · ${challengerNickname}의 미션: $challengerMission
            · ${opponentNickname}의 미션: ${opponentMission ?: PENDING_MISSION}
            · 내기: $betContent
            · 마감: $date 24:00

            서명한 맹세는 무를 수 없다.
        """.trimIndent()
    }

    // ─── 마감 표기 ───────────────────────────────────────────────
    //
    // 🔴 `deadline` 을 그대로 포맷하지 않고 `challengeDate 24:00` 으로 적는다.
    //
    //   KstDeadlineCalculator:  challengeDate=07-31  →  deadline=08-01 00:00   (같은 순간)
    //   화면(design.md §4.2):   "오늘 자정 / 7/31 24:00"
    //
    // deadline 을 그대로 쓰면 **계약서만 "8월 1일" 이라고 적는다.** 사용자는 "오늘(7/31) 자정" 으로
    // 이해하고 서명했는데 계약서엔 다른 날짜가 있는 것이다 — 같은 순간을 두 날짜로 적는 셈이라
    // "어? 내일 마감이었나?" 가 된다. design 이 화면에서 `24:00` 표기를 택한 이유가 정확히 이거고
    // 계약서도 맞춘다.
    //
    // **값이 아니라 표기만 바뀐다**: deadline 은 항상 `challengeDate + 1일 00:00`
    // (KstDeadlineCalculator.endOfDay) 이므로 `challengeDate 24:00` 과 정확히 같은 순간이다.
    // 그래서 render 가 deadline 을 인자로 받을 필요도 없어졌다.

    // ─── 조사 ────────────────────────────────────────────────────
    //
    // "와(과)" / "은(는)" 병기는 관공서 양식 문투다. **개인 이름이 들어간 계약서**에 남으면 조잡하고,
    // 기존 템플릿은 조사가 띄어져 나오기까지 했다("도윤 와(과)").
    //
    // 받침 판정: 한글 음절은 U+AC00 부터 `(초성×21 + 중성)×28 + 종성` 순으로 배열되므로
    // `(code - 0xAC00) % 28` 이 종성 인덱스다. 0 이면 받침 없음.

    /**
     * 숫자의 한국어 읽기에 받침이 있는지 (index = 숫자).
     *
     * **숫자는 영문과 다르다 — 읽기가 하나로 정해져 있어 추정이 아니다.**
     * ```
     * 0 영/십/백/천/만   1 일   3 삼   6 육   7 칠   8 팔   → 받침 있음
     * 2 이   4 사   5 오   9 구                            → 받침 없음
     * ```
     * 여러 자리여도 **마지막 자리가 읽기의 끝을 결정한다** (12 → 십`이`, 13 → 십`삼`).
     * `0` 으로 끝나면 십/백/천/만 중 하나로 끝나는데 **전부 받침이 있다.**
     */
    private val DIGIT_HAS_FINAL = booleanArrayOf(
        true, true, false, true, false, false, true, true, true, false,
    )

    /** 마지막 글자에 받침이 있으면 true. **판정 불가면 null** (영문·이모지 등). */
    private fun hasFinalConsonant(name: String): Boolean? {
        val last = name.trimEnd().lastOrNull() ?: return null
        if (last in '가'..'힣') return (last.code - 0xAC00) % 28 != 0
        // 🔴 숫자로 끝나는 닉네임("테스터3", "우건1")이 실제로 흔하다. 한글이 아니라고 기본값으로
        //    떨어뜨리면 "테스터3와" 가 나오는데 `3`=삼 이라 "테스터3과" 가 맞다.
        //    (2026-08-03 mobile-dev 가 실서버 계약서 본문에서 발견.)
        if (last in '0'..'9') return DIGIT_HAS_FINAL[last - '0']
        return null
    }

    /**
     * 판정 불가(영문·이모지) 닉네임은 **받침 없음 쪽으로 떨어뜨린다.**
     *
     * 영문 이름의 조사는 한글 발음을 따라 갈리는데(`Kim` → "과", `Sua` → "와") **표기만으로는
     * 발음이 정해지지 않는다.** 서버가 추정하면 반드시 틀리는 경우가 생기므로 한쪽으로 고정한다.
     * 병기("와(과)")로 되돌아가는 것은 이 수정이 없애려던 바로 그 문투다.
     *
     * **숫자를 여기 함께 두지 않는 이유**: 숫자는 읽기가 하나로 정해져 있다.
     * **정해진 걸 모른 척하는 것과, 정해지지 않은 걸 찍는 것은 다르다.**
     */
    private fun wa(name: String): String = if (hasFinalConsonant(name) == true) "과" else "와"

    private fun neun(name: String): String = if (hasFinalConsonant(name) == true) "은" else "는"
}
