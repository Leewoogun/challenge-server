import com.lwg.challenge.configureKotlin
import com.lwg.challenge.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

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

            dependencies {
                "implementation"(libs.kotlin.reflect)
                "testImplementation"(libs.kotlin.test.junit5)
            }
        }
    }
}
