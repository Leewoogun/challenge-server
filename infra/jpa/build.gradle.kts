plugins {
    alias(libs.plugins.challenge.spring.library)
    alias(libs.plugins.challenge.spring.jpa)
}

dependencies {
    implementation(projects.core)
    api(projects.infra.entity)  // JpaRepository<UserEntity, Long> 시그니처에 UserEntity 노출 → api
    // UserJpaRepository 인터페이스가 JpaRepository<...> 를 상속하므로
    // 소비 모듈(:infra:repositoryimpl)이 슈퍼타입을 해석하려면 spring-data-jpa 가 노출되어야 한다.
    api(libs.spring.boot.starter.data.jpa)
}
