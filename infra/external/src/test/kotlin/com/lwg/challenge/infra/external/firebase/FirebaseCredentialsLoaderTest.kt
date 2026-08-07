package com.lwg.challenge.infra.external.firebase

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText

/**
 * [FirebaseCredentialsLoader] 격리 검증 (push-fcm T-B4, 계약 §0.4).
 *
 * ## 🔴 이 테스트가 지키는 문장은 하나다 — **설정 오류가 서버를 죽이면 안 된다**
 *
 * `dev-test-login` 에서 `getProperty(Boolean::class)` 가 오타에 `ConversionFailedException` 을
 * 던져 서버를 기동 실패시켰다. 계약 §0.4 가 그 교훈을 명문화했다:
 * **fail-safe 는 "안 열린다"여야지 "터진다"가 아니다.**
 *
 * 그래서 여기서는 **반환값보다 "던지지 않는다"가 본질**이다. 모든 케이스를
 * [assertDoesNotThrow] 로 감싼 이유가 그것이다 — null 을 돌려주는지보다
 * **예외가 새어나가 컨텍스트 로드를 깨뜨리지 않는지**가 먼저다.
 *
 * ⚠️ **성공 경로(유효한 서비스 계정 키)는 테스트하지 않는다.** 진짜 키가 있어야 하는데
 * 그 키는 레포에 둘 수 없고(비공개 키), 가짜로 만들면 `GoogleCredentials` 파싱을 통과시키려
 * RSA 개인키를 넣어야 해서 **"레포에 개인키처럼 보이는 파일"을 만드는 셈**이 된다.
 * 실제 발송 검증은 사용자가 키를 발급한 뒤 실기로 한다 (spec T-U3).
 */
class FirebaseCredentialsLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `경로가 null 이면 null 을 반환하고 던지지 않는다`() {
        val result = assertDoesNotThrow { FirebaseCredentialsLoader.load(null) }
        assertNull(result)
    }

    @Test
    fun `경로가 빈 문자열이면 null - 현재 정상 상태다`() {
        // application.yml 의 기본값이 빈 문자열이다. 즉 이게 **지금 이 프로젝트의 기본 경로**다.
        val result = assertDoesNotThrow { FirebaseCredentialsLoader.load("") }
        assertNull(result)
    }

    @Test
    fun `경로가 공백뿐이어도 null`() {
        val result = assertDoesNotThrow { FirebaseCredentialsLoader.load("   ") }
        assertNull(result)
    }

    /** 환경변수에 경로를 넣었는데 파일을 아직 안 받아둔 흔한 실수. */
    @Test
    fun `파일이 없으면 null 이고 던지지 않는다`() {
        val missing = tempDir.resolve("없는키.json").toString()

        val result = assertDoesNotThrow { FirebaseCredentialsLoader.load(missing) }

        assertNull(result)
    }

    /** 경로 끝을 빠뜨려 디렉터리를 가리키는 경우. `FileInputStream` 은 여기서 IOException 을 던진다. */
    @Test
    fun `디렉터리를 가리키면 null 이고 던지지 않는다`() {
        val dir = tempDir.resolve("keys").also { it.createDirectory() }.toString()

        val result = assertDoesNotThrow { FirebaseCredentialsLoader.load(dir) }

        assertNull(result)
    }

    /** 🔴 계약 §0.4 가 "손상된 JSON" 을 콕 집어 명시한 케이스. */
    @Test
    fun `손상된 JSON 이면 null 이고 던지지 않는다`() {
        val broken = tempDir.resolve("broken.json")
        broken.writeText("{ this is not json ")

        val result = assertDoesNotThrow { FirebaseCredentialsLoader.load(broken.toString()) }

        assertNull(result)
    }

    /** JSON 문법은 맞지만 서비스 계정 형식이 아닌 경우 — 다른 파일을 잘못 지정한 상황. */
    @Test
    fun `JSON 이지만 서비스 계정 형식이 아니면 null 이고 던지지 않는다`() {
        val wrong = tempDir.resolve("wrong.json")
        wrong.writeText("""{"hello":"world"}""")

        val result = assertDoesNotThrow { FirebaseCredentialsLoader.load(wrong.toString()) }

        assertNull(result)
    }

    /** 서비스 계정처럼 생겼지만 키가 유효하지 않은 경우 — 가장 헷갈리는 실패다. */
    @Test
    fun `type 은 service_account 인데 내용이 불완전해도 null 이고 던지지 않는다`() {
        val partial = tempDir.resolve("partial.json")
        partial.writeText("""{"type":"service_account","project_id":"maengse-dev"}""")

        val result = assertDoesNotThrow { FirebaseCredentialsLoader.load(partial.toString()) }

        assertNull(result)
    }
}
