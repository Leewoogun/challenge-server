import com.lwg.challenge.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

/**
 * Spring-aware library module (not bootable).
 * Android의 hmm.android.library에 해당.
 *
 * - kotlin("plugin.spring") 적용 → @Configuration, @Service 등에 open 자동 추가
 * - spring-dependency-management 적용 → Spring BOM으로 버전 자동 관리
 */
class SpringLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "challenge.kotlin.library")
            apply(plugin = "org.jetbrains.kotlin.plugin.spring")
            apply(plugin = "io.spring.dependency-management")

            dependencies {
                "implementation"(libs.spring.context)
                "implementation"(libs.jackson.module.kotlin)
            }
        }
    }
}
