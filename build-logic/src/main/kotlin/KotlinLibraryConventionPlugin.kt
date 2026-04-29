import com.lwg.challenge.configureKotlin
import com.lwg.challenge.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Pure Kotlin library module.
 * Android의 hmm.jvm.library에 해당.
 */
class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension> {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(17))
                }
            }

            configureKotlin()

            // JUnit 5 (JUnit Platform) 사용 — 없으면 JUnit 4 기본이라 @Test (JUnit 5)가 인식 안 됨
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
            }

            dependencies {
                "implementation"(libs.kotlin.reflect)
                "testImplementation"(libs.kotlin.test.junit5)
                // Spring Boot BOM이 관리하는 junit-platform-launcher 정렬. 없으면 JUnit5 엔진이 discover 실패.
                "testRuntimeOnly"(libs.junit.platform.launcher)
            }
        }
    }
}
