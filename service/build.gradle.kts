plugins {
    alias(libs.plugins.challenge.spring.library)
}

dependencies {
    implementation(projects.core)
    implementation(projects.domain.repository)
    implementation(libs.spring.tx)
    implementation(libs.slf4j.api)
}
