plugins {
    alias(libs.plugins.challenge.spring.library)
}

dependencies {
    implementation(projects.core)
    implementation(projects.domain)
    implementation(projects.infra)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa) // AuthService가 UserJpaRepository(JpaRepository 상속)를 참조
    implementation(libs.spring.tx)
    implementation(libs.springdoc.openapi)
    implementation(libs.jjwt.api)
}
