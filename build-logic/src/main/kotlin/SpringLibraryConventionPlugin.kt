import com.lwg.challenge.libs
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.springframework.boot.gradle.plugin.SpringBootPlugin

/**
 * Spring-aware library module (not bootable).
 * Android의 hmm.android.library에 해당.
 *
 * - kotlin("plugin.spring") 적용 → @Configuration, @Service 등에 open 자동 추가
 * - spring-dependency-management 적용 + Spring Boot BOM import → 버전 없이 starter 의존성 선언 가능
 *   (기본 버전은 libs.versions.toml의 spring-boot로부터. :app은 spring-boot 플러그인이 자동으로 BOM을 import하지만,
 *    library 모듈은 spring-boot 플러그인을 적용하지 않으므로 여기서 명시적으로 import한다.)
 */
class SpringLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "challenge.kotlin.library")
            apply(plugin = "org.jetbrains.kotlin.plugin.spring")
            apply(plugin = "io.spring.dependency-management")

            extensions.configure<DependencyManagementExtension> {
                imports {
                    mavenBom(SpringBootPlugin.BOM_COORDINATES)
                }
            }

            dependencies {
                "implementation"(libs.spring.context)
                "implementation"(libs.jackson.module.kotlin)
            }
        }
    }
}
