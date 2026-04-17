import com.lwg.challenge.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

/**
 * JPA support addon.
 * Android의 hmm.android.room에 해당.
 *
 * - kotlin("plugin.jpa") 적용 → @Entity, @MappedSuperclass, @Embeddable에 no-arg 생성자 자동 추가
 * - allOpen 설정 → JPA 프록시를 위해 Entity 클래스를 open으로
 * - spring-boot-starter-data-jpa 의존성 추가
 */
class SpringJpaConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "org.jetbrains.kotlin.plugin.jpa")

            dependencies {
                "implementation"(libs.spring.boot.starter.data.jpa)
                "runtimeOnly"(libs.postgresql)
            }
        }
    }
}
