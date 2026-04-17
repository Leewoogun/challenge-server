import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply

/**
 * Spring Boot application module (bootable JAR).
 * Android의 hmm.android.application에 해당.
 *
 * - spring-boot 플러그인 적용 → bootJar, bootRun 태스크 사용 가능
 * - 오직 app 모듈에서만 사용
 */
class SpringBootConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "challenge.spring.library")
            apply(plugin = "org.springframework.boot")
        }
    }
}
