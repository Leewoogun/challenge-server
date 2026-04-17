plugins {
    alias(libs.plugins.challenge.spring.library)
}

dependencies {
    implementation(projects.core)
    implementation(projects.domain)
}
