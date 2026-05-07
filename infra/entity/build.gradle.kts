plugins {
    alias(libs.plugins.challenge.spring.library)
    alias(libs.plugins.challenge.spring.jpa)
}

dependencies {
    implementation(projects.core)
    api(projects.domain.model)  // UserEntity.toDomain() returns User; fromDomain() takes User. api 로 노출.
}
