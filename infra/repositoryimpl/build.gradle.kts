plugins {
    alias(libs.plugins.challenge.spring.library)
}

dependencies {
    implementation(projects.core)
    implementation(projects.domain.repository)
    implementation(projects.infra.entity)
    implementation(projects.infra.jpa)
}
