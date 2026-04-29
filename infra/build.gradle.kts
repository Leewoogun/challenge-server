plugins {
    alias(libs.plugins.challenge.spring.library)
    alias(libs.plugins.challenge.spring.jpa)
}

dependencies {
    implementation(projects.core)
    implementation(projects.domain)
    implementation(libs.spring.boot.starter.data.redis)
    // WebClient (Kakao OAuth 등 외부 호출). tomcat은 app에서 유지하고 여기선 WebFlux 코어만 사용.
    implementation(libs.spring.boot.starter.webflux)
}
