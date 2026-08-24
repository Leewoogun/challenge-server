package com.lwg.challenge.infra.external.photo

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * [LocalDiskPhotoStorage] 단위 테스트 (challenge-verification T-B1, ADR-0011).
 *
 * 실제 파일시스템(`@TempDir`)을 쓴다 — fake 로 대체하면 **이 클래스가 하는 일이 전부 사라진다.**
 * 경로 결정·원자적 쓰기·영속성이 검증 대상이기 때문이다.
 *
 * 커버하는 spec 수용 기준:
 * - 🔴 **서버 재기동 후에도 이전에 올린 사진이 보인다** (`재기동 후에도 사진이 남아 있다`)
 *
 * 서명·만료 관련 검증은 조회 서명 URL 폐기(2026-08-18 2차 결정)와 함께 사라졌다 — 접근 제어는
 * `VerificationService.readPhoto` 가 요청 시점 JWT 로 하고, 여기는 **바이트 왕복**만 본다.
 *
 * ⚠️ 크기 상한은 `spring.servlet.multipart` 가, JPEG 검사는 `VerificationService` 가 진다 —
 * 여기는 *"받은 바이트를 안전한 자리에 쓰고 되돌려주는 일"* 만 검증한다.
 */
class LocalDiskPhotoStorageTest {

    @TempDir
    lateinit var root: Path

    private companion object {
        /** 최소한의 JPEG 매직 넘버 + 패딩. */
        fun jpeg(size: Int = 32): ByteArray =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(size - 3) { 0x11 }
    }

    private fun storage() = LocalDiskPhotoStorage(root)

    // ─── 저장 ────────────────────────────────────────────────────

    @Test
    fun `store 는 디스크에 파일을 만들고 규칙에 맞는 key 를 돌려준다`() {
        val bytes = jpeg(64)

        val key = storage().store(7, 12, bytes)

        assertTrue(Regex("""^7/12/[0-9a-f]{32}\.jpg$""").matches(key), key)
        val file = root.resolve(key)
        assertTrue(Files.isRegularFile(file), "디스크에 파일이 실제로 생겨야 한다: $file")
        assertTrue(Files.readAllBytes(file).contentEquals(bytes))
    }

    /** 🔴 부를 때마다 새 key — 항상 빈 자리에 쓰므로 이전 사진을 덮어쓸 수 없다. */
    @Test
    fun `같은 조합이라도 부를 때마다 다른 key 가 나오고 이전 파일을 덮지 않는다`() {
        val s = storage()
        val first = s.store(7, 12, jpeg(32))
        val second = s.store(7, 12, jpeg(64))

        assertNotEquals(first, second)
        assertTrue(Files.readAllBytes(root.resolve(first)).contentEquals(jpeg(32)))
        assertTrue(Files.readAllBytes(root.resolve(second)).contentEquals(jpeg(64)))
    }

    // ─── 저장 → 읽기 왕복 ────────────────────────────────────────

    @Test
    fun `저장한 바이트를 read 로 그대로 되돌려준다`() {
        val s = storage()
        val bytes = jpeg(64)
        val key = s.store(7, 12, bytes)

        assertArrayEquals(bytes, s.read(key))
    }

    /**
     * 🔴 spec 수용 기준: *"서버 재기동 후에도 이전에 올린 사진이 보인다 (로컬 폴더 영속 확인)."*
     *
     * 새 인스턴스가 같은 루트를 보면 이전 파일을 그대로 읽어야 한다 — 메모리에 든 상태가
     * 없어야 성립한다.
     */
    @Test
    fun `재기동 후에도 사진이 남아 있다`() {
        val bytes = jpeg(48)
        val key = storage().store(7, 12, bytes)

        // "재기동" — 완전히 새 인스턴스, 같은 루트.
        assertArrayEquals(bytes, storage().read(key))
    }

    // ─── 읽기 거절 ───────────────────────────────────────────────

    @Test
    fun `저장하지 않은 key 는 null 이다`() {
        assertNull(storage().read(PhotoKeys.newKey(7, 12)))
    }

    /**
     * 🔴 key 규칙 밖의 문자열은 **예외가 아니라 null** 이다 — 파일시스템에 닿기 전에 거부된다
     * (`resolve` 의 루트 검사 앞단). 예외로 답하면 서빙 경로에서 500 이 새 나간다.
     */
    @Test
    fun `모양이 틀린 key 는 예외 없이 null 이다`() {
        val s = storage()

        assertNull(s.read("../../etc/passwd"))
        assertNull(s.read("../escape.jpg"))
        assertNull(s.read("7/12/whatever.jpg"))
        assertNull(s.read(""))
    }

    // ─── 기타 ────────────────────────────────────────────────────

    @Test
    fun `delete 는 파일을 지우고 없는 key 에는 아무 일도 하지 않는다`() {
        val s = storage()
        val key = s.store(7, 12, jpeg())

        s.delete(key)
        assertFalse(Files.exists(root.resolve(key)))
        s.delete(key) // 두 번째 호출이 터지지 않는다
        s.delete("../../etc/passwd")
    }
}
