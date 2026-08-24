package com.lwg.challenge.config

import com.lwg.challenge.domain.photo.PhotoStorage
import com.lwg.challenge.infra.external.photo.LocalDiskPhotoStorage
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

/**
 * 인증 사진 저장 빈 배선 (ADR-0011, challenge-verification T-B1).
 *
 * ## 왜 `NotificationSenderConfig` 처럼 `:infra:external` 에 두지 않았나
 *
 * 원래는 `PhotoBaseUrlProvider` 구현이 **서블릿 요청 컨텍스트**를 봐야 해서 `:app` 에 있었다.
 * 조회 서명 URL 폐기(2026-08-18 2차 결정)로 그 조각이 사라져 **이제 `:infra:external` 에 둘 수도
 * 있지만**, `SecurityConfig`/`ClockConfig` 와 같은 자리를 지키는 편이 찾기 쉬우므로 위치는 유지한다.
 *
 * ## 🔴 여기서는 fail-safe 로 떨어지지 않는다 — `NotificationSenderConfig` 와 반대다
 *
 * 푸시는 **없어도 앱이 동작**하므로 키가 없으면 no-op 으로 떨어지는 게 맞았다(계약 §0.4).
 * 사진 저장은 다르다 — **없으면 인증 자체가 불가능**하고, 조용히 비활성화되면 그 사실이
 * *"업로드가 안 된다"* 라는 런타임 증상으로만 드러난다. 저장 루트를 못 만드는 것은
 * **설정 오류**이므로 기동 시점에 터뜨린다.
 */
@Configuration
class PhotoStorageConfig(
    /**
     * 저장 루트. **레포 밖 기본값**을 쓴다 — `./` 아래에 두면 `git status` 에 사진이 뜨고
     * `./gradlew clean` 이 지울 수 있다. spec 수용 기준의 *"서버 재기동 후에도 사진이 보인다"*
     * 를 기본값 상태로 만족해야 한다.
     *
     * 상대 경로는 서버 실행 디렉터리(`challenge-server/`) 기준 — `../challenge-image` 는
     * `challenge/` 아래의 형제 폴더다.
     */
    @Value("\${challenge.photo.storage-root:../challenge-image}")
    private val storageRoot: String,
) {

    @Bean
    fun localDiskPhotoStorage(): PhotoStorage = LocalDiskPhotoStorage(
        root = Path.of(storageRoot).toAbsolutePath().normalize(),
    )
}
