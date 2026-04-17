import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.spring.boot.gradlePlugin)
    compileOnly(libs.spring.dependency.management.gradlePlugin)

    // convention plugin에서 타입세이프 버전 카탈로그 접근자(libs.xxx) 사용을 위한 workaround.
    // 이걸로 org.gradle.accessors.dm.LibrariesForLibs 클래스가 classpath에 노출됨.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}

gradlePlugin {
    plugins {
        register("kotlinLibrary") {
            id = libs.plugins.challenge.kotlin.library.get().pluginId
            implementationClass = "KotlinLibraryConventionPlugin"
        }
        register("springLibrary") {
            id = libs.plugins.challenge.spring.library.get().pluginId
            implementationClass = "SpringLibraryConventionPlugin"
        }
        register("springBoot") {
            id = libs.plugins.challenge.spring.boot.get().pluginId
            implementationClass = "SpringBootConventionPlugin"
        }
        register("springJpa") {
            id = libs.plugins.challenge.spring.jpa.get().pluginId
            implementationClass = "SpringJpaConventionPlugin"
        }
    }
}
