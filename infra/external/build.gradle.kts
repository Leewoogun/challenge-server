plugins {
    alias(libs.plugins.challenge.spring.library)
}

dependencies {
    implementation(projects.core)
    implementation(projects.domain.repository)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.data.redis)
    // FCM 발송 (push-fcm T-B4). 의존성이 있다고 발송이 켜지는 게 아니다 —
    // 서비스 계정 키가 없으면 NoOpNotificationSender 로 떨어진다.
    implementation(libs.firebase.admin)
    implementation(libs.slf4j.api)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
