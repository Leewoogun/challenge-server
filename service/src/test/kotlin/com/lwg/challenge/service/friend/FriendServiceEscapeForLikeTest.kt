package com.lwg.challenge.service.friend

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `FriendService.escapeForLike` 단위 테스트.
 *
 * LIKE 와일드카드(`%`, `_`) 와 escape 문자 자체(`\`) 가 올바르게 escape 되는지 검증.
 * replace 순서가 바뀌면 (예: `%` 를 `\` 보다 먼저 처리) `\` 를 이중 escape 하여 패턴이 깨진다.
 * 본 테스트가 그 회귀를 막는다. native query 의 `ESCAPE '\'` 절과 짝을 이뤄야 함.
 */
class FriendServiceEscapeForLikeTest {

    @Test
    fun `평범한 한글 닉네임은 그대로 반환`() {
        assertEquals("이우", FriendService.escapeForLike("이우"))
    }

    @Test
    fun `퍼센트는 backslash-percent 로 escape`() {
        // "50%" → "50\%"  (그대로 LIKE 에 넣으면 "%50\%%" → ESCAPE '\' 와 함께 literal '%' 매칭)
        assertEquals("50\\%", FriendService.escapeForLike("50%"))
    }

    @Test
    fun `언더스코어는 backslash-underscore 로 escape`() {
        // "a_b" → "a\_b"  (LIKE 의 단일 문자 wildcard 차단)
        assertEquals("a\\_b", FriendService.escapeForLike("a_b"))
    }

    @Test
    fun `backslash 가 가장 먼저 escape 되어야 한다 - 순서 회귀 방지`() {
        // "a\b" → "a\\b". 만약 `%` / `_` 가 먼저 처리되면 그들의 escape 결과(`\%`, `\_`)에
        // 들어있는 `\` 가 뒤이은 `replace("\\", "\\\\")` 에 의해 다시 escape 되어
        // `\\%`, `\\_` 가 된다 — LIKE escape 의미가 깨짐.
        assertEquals("a\\\\b", FriendService.escapeForLike("a\\b"))
    }

    @Test
    fun `세 와일드카드가 동시에 등장해도 각각 한 번씩만 escape`() {
        // "a\b%c_d" → "a\\b\%c\_d"
        // 길이로 한 번 더 검증: 입력 7글자 + 3개 escape backslash = 10
        val actual = FriendService.escapeForLike("a\\b%c_d")
        assertEquals("a\\\\b\\%c\\_d", actual)
        assertEquals(10, actual.length)
    }

    @Test
    fun `빈 문자열은 빈 문자열 그대로`() {
        assertEquals("", FriendService.escapeForLike(""))
    }
}
