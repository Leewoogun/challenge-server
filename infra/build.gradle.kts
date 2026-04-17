plugins {
    alias(libs.plugins.challenge.spring.library)
    alias(libs.plugins.challenge.spring.jpa)
}

dependencies {
    implementation(projects.core)
    implementation(projects.domain)
    implementation(libs.spring.boot.starter.data.redis)
}
