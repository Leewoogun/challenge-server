package com.lwg.challenge.infra.external.photo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [PhotoKeys] 단위 테스트 (challenge-verification T-B1).
 *
 * 🔴 spec T-B1 의 *"사용자 입력을 파일명에 그대로 쓰지 않는다 (경로 탈출)"* 를 고정한다.
 * 여기가 뚫리면 뒤의 권한 검사가 다 통과해도 남의 파일을 읽거나 지울 수 있다.
 */
class PhotoKeysTest {

    @Test
    fun `key 는 challengeId 와 userId 로 시작하고 난수 파일명으로 끝난다`() {
        val key = PhotoKeys.newKey(challengeId = 7, userId = 12)

        assertTrue(key.startsWith("7/12/"), "앞 두 조각이 id 여야 제출 확정에서 소유자를 가릴 수 있다: $key")
        assertTrue(key.endsWith(".jpg"), key)
        assertTrue(PhotoKeys.isValid(key), key)
    }

    /**
     * 🔴 `store` 가 *"항상 빈 자리에 쓴다"* 는 성질이 여기에 기댄다. key 가 id 조합만으로
     * 결정되면 두 번째 저장이 첫 사진을 덮어쓴다.
     */
    @Test
    fun `부를 때마다 다른 key 가 나온다`() {
        val keys = (1..200).map { PhotoKeys.newKey(1, 1) }.toSet()

        assertEquals(200, keys.size, "난수가 겹치면 재제출 판정이 무너진다")
    }

    /**
     * 🔴 `PhotoKeys.require()` 삭제(2026-08-18) 후 **경로 탈출 방어의 단독 고정점**이다.
     * 던지는 검증이 사라졌으니 정규식이 뚫리면 잡아줄 두 번째 그물이 없다.
     * `LocalDiskPhotoStorage.read`/`delete` 는 여기 [PhotoKeys.isValid] 의 `false` 하나로
     * 각각 `null`/no-op 이 되어 fail-soft 한다 — 이 테스트가 그 전제를 지킨다.
     */
    @Test
    fun `경로 탈출 시도는 전부 거부된다`() {
        val attacks = listOf(
            "../../../etc/passwd",
            "7/12/../../../etc/passwd",
            "7/12/../13/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg",
            "/etc/passwd",
            "7/12/..%2f..%2fpasswd.jpg",
            "7/12/a.jpg/../../b.jpg",
        )

        attacks.forEach { assertFalse(PhotoKeys.isValid(it), "거부되어야 한다: $it") }
    }

    @Test
    fun `모양이 다른 key 는 거부된다`() {
        val invalid = listOf(
            "",
            "7/12",                                          // 조각 부족
            "7/12/13/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg",  // 조각 초과
            "a/12/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg",     // challengeId 가 숫자가 아니다
            "7/12/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",     // 확장자
            "7/12/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA.jpg",     // 대문자 hex 는 안 만든다
            "7/12/aaaaaaaa.jpg",                             // 난수 길이 부족
            "7/12/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaag.jpg",    // hex 가 아닌 문자
        )

        invalid.forEach { assertFalse(PhotoKeys.isValid(it), "거부되어야 한다: $it") }
    }
}
