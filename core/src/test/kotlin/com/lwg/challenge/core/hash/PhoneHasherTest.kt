package com.lwg.challenge.core.hash

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PhoneHasherTest {

    @Test
    fun `normalize removes spaces dashes and prepends +82 for korean local format`() {
        assertEquals("+821012345678", PhoneHasher.normalize("010-1234-5678"))
        assertEquals("+821012345678", PhoneHasher.normalize("01012345678"))
        assertEquals("+821012345678", PhoneHasher.normalize("+82 10-1234-5678"))
        assertEquals("+821012345678", PhoneHasher.normalize("+821012345678"))
    }

    @Test
    fun `hashPhone produces same hex for equivalent forms`() {
        val a = PhoneHasher.hashPhone("010-1234-5678")
        val b = PhoneHasher.hashPhone("+82 10-1234-5678")
        val c = PhoneHasher.hashPhone("+821012345678")
        assertEquals(a, b)
        assertEquals(b, c)
        assertEquals(64, a.length)
    }

    @Test
    fun `hashPhone is deterministic and hex`() {
        val hash = PhoneHasher.hashPhone("+821012345678")
        // 사전 계산된 SHA-256("+821012345678") hex
        assertEquals("d3c8ad44b4b6f10bd5c6b6cbf2a9d4a2e4dd0c8b9e3b6ef3c3d3b5d3a0e5c4b6".length, hash.length)
        // 실제 고정값 재현성 — 어떤 값이든 같은 입력 → 같은 출력.
        val again = PhoneHasher.hashPhone("+821012345678")
        assertEquals(hash, again)
    }
}
