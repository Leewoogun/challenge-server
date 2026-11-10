plugins {
    alias(libs.plugins.challenge.spring.boot)
}

// Spring Boot BOM 이 testcontainers 버전을 관리해 version catalog 값을 덮어쓴다(전이 의존성만 1.21.0 으로 끌려가
// 모듈 간 버전이 섞였다). BOM 속성을 catalog 값으로 맞춰 한 버전으로 고정한다.
extra["testcontainers.version"] = libs.versions.testcontainers.get()

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
    // /actuator/health — docker compose healthcheck 용. 노출 범위는 application.yml management.* 참조.
    implementation(libs.spring.boot.starter.actuator)

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
