package com.lwg.challenge.infra.external.photo

import com.lwg.challenge.domain.photo.PhotoStorage
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * [PhotoStorage] 의 **서버 로컬 폴더** 구현 (ADR-0011 §1, challenge-verification T-B1).
 *
 * 외부 서비스·계정·시크릿이 **0개**다. 개인 프로젝트이고 서버 호스팅이 아직 미정이라
 * *"오늘 당장 정상 동작하는 유일한 선택"* 이다.
 *
 * ## 🔴 이 클래스가 지고 있는 부채 (ADR-0011 이 인지하고 받은 것)
 *
 * - **서버가 죽으면 사진도 죽는다.** 백업이 없다.
 * - **PaaS 형 호스팅으로 가면 깨진다** — 재배포 시 파일이 사라진다. VM 형(디스크 영속)이면 그대로 쓴다.
 * - 사진 바이트 서빙을 서버가 중계한다.
 *
 * 만기는 **호스팅 결정 시점**이다. 그때 이 클래스를 오브젝트 스토리지 구현체로 갈아끼우면
 * [PhotoStorage] 를 쓰는 서비스와 앱은 **무변경**이다.
 *
 * ## 🔴 URL 을 만들지 않는다 — 조회도 서명 URL 폐기 (2026-08-18 2차 결정)
 *
 * 예전에는 서명·만료를 실은 읽기 URL 을 발급했고, 그 URL 의 `scheme://host:port` 를 알아내는
 * `PhotoBaseUrlProvider` 조각이 따로 있었다 — *"저장소가 서버 자신이라 URL 의 host 를 모른다*
 * *(Android 에뮬레이터 `10.0.2.2` / iOS 시뮬레이터 `localhost`)"* 때문이었다.
 * **서버가 URL 을 만들지 않게 되면서 그 문제 자체가 소멸했다** — 앱이 자기 baseUrl 에
 * 상대 경로를 붙여 부른다. 서명기·base-url·TTL 이 전부 빠져 이 클래스에 남는 설정은
 * [root] 하나다.
 *
 * @param root 저장 루트. **설정값**이다 (spec T-B1 — 로컬/배포에서 달라진다, ADR-0007).
 */
class LocalDiskPhotoStorage(
    private val root: Path,
) : PhotoStorage {

    private val log = LoggerFactory.getLogger(LocalDiskPhotoStorage::class.java)

    override fun store(challengeId: Long, userId: Long, bytes: ByteArray): String {
        val key = PhotoKeys.newKey(challengeId, userId)
        writeAtomically(resolve(key), bytes)
        return key
    }

    override fun read(key: String): ByteArray? {
        if (!PhotoKeys.isValid(key)) return null
        val path = resolve(key)
        if (!Files.isRegularFile(path)) return null
        return Files.readAllBytes(path)
    }

    override fun delete(key: String) {
        if (!PhotoKeys.isValid(key)) return
        Files.deleteIfExists(resolve(key))
    }

    // ─── 내부 ────────────────────────────────────────────────────

    /**
     * key 를 실제 경로로 바꾼다.
     *
     * 🔴 [PhotoKeys] 정규식이 이미 경로 탈출을 막지만 **`normalize()` 후 루트 안인지 한 번 더 본다.**
     * 파일시스템에 닿는 마지막 지점이고, key 규칙이 나중에 느슨해져도 여기가 남는다.
     */
    private fun resolve(key: String): Path {
        val resolved = root.resolve(key).normalize()
        require(resolved.startsWith(root)) { "저장 루트를 벗어나는 경로입니다" }
        return resolved
    }

    /**
     * 임시 파일에 쓰고 옮긴다.
     *
     * 🔴 목적지에 직접 쓰면 **쓰기가 중간에 끊겼을 때 잘린 파일이 그 key 에 남는다.**
     * 옮기기는 같은 파일시스템 안에서 원자적이므로, key 에는 완성된 파일만 나타난다.
     *
     * ⚠️ `REPLACE_EXISTING` 이 붙어 있지만 덮어쓰기 걱정은 없다. 예전에는 앱이 발급받은 key 를
     * 재사용해 같은 자리에 다른 바이트를 올리는 경로가 있어 덮어쓰기 거부(409)가 필요했다.
     * **이제 key 는 [store] 호출마다 서버가 새로 만들고 앱은 key 를 보지도 못하므로 덮어쓸
     * 대상이 존재하지 않는다.** (POSIX `rename(2)` 는 어차피 목적지를 대체하고, `ATOMIC_MOVE` 가
     * 있으면 다른 옵션이 무시된다.)
     */
    private fun writeAtomically(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".upload-", ".tmp")
        try {
            Files.write(temp, bytes)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    init {
        Files.createDirectories(root)
        log.info("인증 사진 저장 루트: {}", root.toAbsolutePath())
    }
}
