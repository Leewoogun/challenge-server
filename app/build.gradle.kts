plugins {
    alias(libs.plugins.challenge.spring.boot)
}

dependencies {
    implementation(projects.core)
    implementation(projects.domain.model)
    implementation(projects.domain.repository)
    implementation(projects.controller)
    implementation(projects.service)
    implementation(projects.infra.entity)
    implementation(projects.infra.jpa)
    implementation(projects.infra.repositoryimpl)
    implementation(projects.infra.external)
    implementation(projects.batch)
    // NOTE: 기존 :api, :domain (단일), :infra (단일) 모듈은 Task 9에서 삭제됨

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.websocket)

    runtimeOnly(libs.postgresql)

    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}
