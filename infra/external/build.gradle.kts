plugins {
    alias(libs.plugins.challenge.spring.library)
}

dependencies {
    implementation(projects.core)
    implementation(projects.domain.repository)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.data.redis)
}
