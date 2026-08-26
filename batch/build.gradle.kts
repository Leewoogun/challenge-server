plugins {
    alias(libs.plugins.challenge.spring.library)
}

dependencies {
    implementation(projects.core)
    // :service 가 implementation 스코프라 그 안의 domain 타입은 이 모듈로 전이되지 않는다.
    // 지금은 스케줄러가 domain 타입을 직접 다루지 않지만, 배치가 늘면 곧 필요해지는 자리라 명시적으로 유지한다.
    implementation(projects.domain.model)
    implementation(projects.service)

    // challenge.spring.library 는 spring-context 까지만 준다.
    // ApplicationReadyEvent(기동 시 소급 판정 트리거)는 spring-boot 에, @ConditionalOnProperty(운영 스위치)는
    // spring-boot-autoconfigure 에 있어 둘 다 직접 들인다. starter 를 통째로 들이지 않는 것은
    // 이 모듈이 로깅/웹 설정을 소유하지 않기 때문 — 그건 :app 소관이다.
    implementation(libs.spring.boot)
    implementation(libs.spring.boot.autoconfigure)

    implementation(libs.slf4j.api)
}
