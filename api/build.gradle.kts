plugins {
    alias(libs.plugins.challenge.spring.library)
}

dependencies {
    implementation(projects.core)
    implementation(projects.domain)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.springdoc.openapi)
    implementation(libs.jjwt.api)
}
