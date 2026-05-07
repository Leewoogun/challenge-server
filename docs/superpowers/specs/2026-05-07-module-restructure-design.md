# Module Restructure: Layered Split with DIP

날짜: 2026-05-07
상태: Draft (review pending)

## 배경

현재 멀티모듈 구성은 `app / core / domain / api / infra / batch` 6개 모듈이다. 작업 중 두 가지 불편이 누적됨:

1. **(A) 코드 찾기 불편** — 한 기능(`AuthService`, `AuthController`, `UserJpaRepository`, `UserEntity`)이 `api`와 `infra`에 흩어져 있어, 안드로이드식 모듈 분리에 익숙한 사용자에게 탐색 비용이 크다.
2. **(B) 잘못된 import 컴파일 단 차단 부재** — 패키지로만 구분되어 있어 Controller가 Repository를 직접 부르거나 Service가 JPA API를 직접 사용하는 코드를 막지 못한다.

이번 리팩토링은 위 두 가지를 모듈 경계로 강제하는 것이 목적이다.

## 채택 패턴

- **레이어 단위 모듈 분리** (안드로이드 출신 사용자의 분리 습관과 일치)
- **DIP 적용 (1-B)**: Repository / 외부 서비스 인터페이스를 도메인에 두고, 인프라 모듈이 구현체를 제공한다. Service는 인터페이스만 의존 → 컴파일 단에서 JPA·외부 라이브러리 사용을 차단.
- **`:domain` 추가 분리**: `:domain:model` (도메인 객체 + 비즈니스 예외) + `:domain:repository` (outbound 인터페이스).
- **`:infra` 분리**: `:infra:repositoryimpl` (JPA) + `:infra:external` (Kakao, Redis 등).

## 최종 모듈 트리

```
challenge-server/
├── app
├── core                       # 공용 유틸 + JwtTokenProvider
├── domain/
│   ├── model                  # User, UserStatus, BusinessException 계열, ResponseCode, KakaoUserInfo
│   └── repository             # UserRepository, KakaoAuthPort (outbound 인터페이스)
├── controller                 # (신규) HTTP 진입 + 보안 Filter + DTO + ExceptionHandler + OpenAPI
├── service                    # (신규) 비즈니스 흐름
├── infra/
│   ├── repositoryimpl         # (신규) UserEntity + UserJpaRepository + UserRepositoryImpl + 매퍼
│   └── external               # (신규) KakaoOAuthClient (KakaoAuthPort 구현), Redis 자리
├── batch
└── build-logic
```

기존 `:api`, `:domain` (현행 단일), `:infra` (현행 단일) 모듈은 삭제된다.

## 의존 그래프

```
                          app
                           │
   ┌───────────────────────┼─────────────────────────────┐
   ▼                       ▼                             ▼
controller              service              infra:repositoryimpl  infra:external
   │  │                     │                             │              │
   │  └─────────┬───────────┴─────────────┬───────────────┘              │
   │           ▼                          ▼                              │
   │     domain:repository ◄──────────────────────────────────── (구현)
   │           │
   │           │ api(projects.domain.model)
   │           ▼
   │     domain:model
   │           │
   ▼           ▼
  core ◄──── core
```

핵심 규칙:

- `:controller → :service` (단방향). 역방향 의존 없음.
- `:controller`, `:service` → `:domain:repository` (인터페이스만 봄). `:domain:model`은 `api()` 노출로 transitive 접근.
- `:infra:*` → `:domain:repository` (인터페이스 구현 위해).
- `:service`는 `:infra:*`를 **의존하지 않는다**. JPA/WebClient 클래스 import 시 컴파일 실패 → 목표 (B) 달성.
- `:controller`도 `:infra:*`를 **의존하지 않는다**. Repository 직접 호출 차단.
- `:app`만 모든 인프라 모듈을 implementation으로 묶어 런타임 클래스패스를 구성.

## 파일 이동 매핑

### 기존 `api/` → 새 모듈

| 현재 | 새 위치 |
|---|---|
| `api/auth/AuthController.kt` | `controller/auth/AuthController.kt` |
| `api/auth/JwtAuthenticationFilter.kt` | `controller/auth/JwtAuthenticationFilter.kt` |
| `api/auth/JwtTokenProvider.kt` | `core/auth/JwtTokenProvider.kt` |
| `api/auth/AuthService.kt` | `service/auth/AuthService.kt` |
| `api/auth/dto/KakaoLoginRequest.kt` | `controller/auth/dto/KakaoLoginRequest.kt` |
| `api/auth/dto/RefreshRequest.kt` | `controller/auth/dto/RefreshRequest.kt` |
| `api/auth/dto/RefreshResponse.kt` (RefreshData 포함) | `controller/auth/dto/RefreshResponse.kt` |
| `api/auth/dto/LoginResponse.kt` (LoginData 포함) | **분리** — `LoginResponse` → `controller/auth/dto/`, `LoginData` → `service/auth/LoginResult.kt`로 개명 (아래 참고) |
| `api/common/BaseResponse.kt` | `controller/common/BaseResponse.kt` |
| `api/common/ResponseCode` (object) | `domain/model/common/ResponseCode.kt`로 이동 — `BusinessException`이 참조하므로 도메인 쪽에 둔다 |
| `api/common/exception/BusinessException.kt` | `domain/model/common/exception/BusinessException.kt` (전체 — `BusinessException`, `SnackbarException`, `DialogException`, `FullScreenException`, `OneButtonDialogException`, `UnauthorizedException`) |
| `api/common/exception/GlobalExceptionHandler.kt` | `controller/common/exception/GlobalExceptionHandler.kt` |
| `api/config/OpenAPIConfig.kt` | `controller/config/OpenAPIConfig.kt` |

### 기존 `infra/` → 새 모듈

| 현재 | 새 위치 |
|---|---|
| `infra/auth/UserEntity.kt` | `infra/repositoryimpl/auth/UserEntity.kt` |
| `infra/auth/UserJpaRepository.kt` | `infra/repositoryimpl/auth/UserJpaRepository.kt` |
| `infra/kakao/KakaoOAuthClient.kt` | `infra/external/kakao/KakaoOAuthClient.kt` |
| `infra/kakao/KakaoUserResponse.kt` | `infra/external/kakao/KakaoUserResponse.kt` |
| `infra/kakao/KakaoOAuthExceptions.kt` | `infra/external/kakao/KakaoOAuthExceptions.kt` |

### 기존 `domain/` → 분리

| 현재 | 새 위치 |
|---|---|
| `domain/user/User.kt` (`User` data class + `UserStatus` enum) | `domain/model/user/User.kt` |

### 기존 `core/` (변경 없음, 추가만)

| 현재 | 비고 |
|---|---|
| `core/hash/PhoneHasher.kt` | 그대로 |
| (신규) `core/auth/JwtTokenProvider.kt` | `api/auth/`에서 이동, JJWT 의존성도 `:core`로 이동 |

### 신규 추가 파일 (DIP 어댑터)

| 파일 | 모듈 | 역할 |
|---|---|---|
| `domain/repository/user/UserRepository.kt` | `:domain:repository` | 순수 Kotlin 인터페이스. 메서드는 `AuthService`가 실제 사용하는 것만(`findByKakaoId(kakaoId: Long): User?`, `save(user: User): User` 등) |
| `domain/repository/auth/KakaoAuthPort.kt` | `:domain:repository` | `getUserInfo(accessToken: String): KakaoUserInfo` 같은 도메인 친화 시그니처. 반환 타입은 `:domain:model`에 신규 정의(`KakaoUserInfo`)하거나 `User` 변환 후 반환 — 결정은 implementation plan 단계에서. |
| `infra/repositoryimpl/auth/UserRepositoryImpl.kt` | `:infra:repositoryimpl` | `@Component`. `UserJpaRepository` 위임 + `User ↔ UserEntity` 매핑. `UserEntity.toDomain()`은 이미 존재 → `User.toEntity()` 확장함수 추가. |
| `infra/external/kakao/KakaoAuthAdapter.kt` (또는 `KakaoOAuthClient`가 직접 구현) | `:infra:external` | `KakaoAuthPort` 구현. 기존 `KakaoOAuthClient.getUserInfo()`를 위임 + `KakaoUserResponse → KakaoUserInfo` 매핑. 기존 `KakaoOAuthClient`는 내부 디테일로 유지하거나 통합. |

## 모듈별 build.gradle.kts (요지)

```kotlin
// :core
plugins { alias(libs.plugins.challenge.spring.library) }
dependencies {
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
}

// :domain:model
plugins { alias(libs.plugins.challenge.spring.library) }
dependencies {
    implementation(projects.core)
}

// :domain:repository
plugins { alias(libs.plugins.challenge.spring.library) }
dependencies {
    implementation(projects.core)
    api(projects.domain.model)              // User 등이 인터페이스 시그니처에 노출되므로 api
    // spring-tx 미포함: @Transactional 은 :service 가 사용. 도메인 인터페이스는 프레임워크 비종속.
}

// :service
plugins { alias(libs.plugins.challenge.spring.library) }
dependencies {
    implementation(projects.core)
    implementation(projects.domain.repository)   // model은 transitive
    implementation(libs.spring.tx)               // @Transactional
    // 의도적으로 web/security/jpa/webflux 없음
}

// :controller
plugins { alias(libs.plugins.challenge.spring.library) }
dependencies {
    implementation(projects.core)
    implementation(projects.domain.repository)   // model transitive
    implementation(projects.service)             // AuthService 호출
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.springdoc.openapi)
    // 의도적으로 jpa/webflux 없음
}

// :infra:repositoryimpl
plugins {
    alias(libs.plugins.challenge.spring.library)
    alias(libs.plugins.challenge.spring.jpa)
}
dependencies {
    implementation(projects.core)
    implementation(projects.domain.repository)
    implementation(libs.spring.boot.starter.data.jpa)
}

// :infra:external
plugins { alias(libs.plugins.challenge.spring.library) }
dependencies {
    implementation(projects.core)
    implementation(projects.domain.repository)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.data.redis)
}

// :app
plugins { alias(libs.plugins.challenge.spring.boot) }
dependencies {
    implementation(projects.core)
    implementation(projects.domain.model)
    implementation(projects.domain.repository)
    implementation(projects.controller)
    implementation(projects.service)
    implementation(projects.infra.repositoryimpl)
    implementation(projects.infra.external)
    implementation(projects.batch)

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
```

## settings.gradle.kts

```kotlin
include(":app")
include(":core")
include(":domain:model")
include(":domain:repository")
include(":controller")
include(":service")
include(":infra:repositoryimpl")
include(":infra:external")
include(":batch")
```

`TYPESAFE_PROJECT_ACCESSORS` 가 켜져 있어 `projects.domain.model`, `projects.infra.repositoryimpl` 형식으로 참조 가능.

## 기존 코드와의 충돌 / 처리 방침

### 1. `LoginData`의 위치 — 분리 필요

현재 `LoginResponse.kt` 안에 `LoginResponse`(HTTP 응답 wrapper) + `LoginData`(데이터 본체) 두 클래스가 같이 있다. `AuthService.loginWithKakao()`가 `LoginData`를 반환한다.

`LoginData`에는 Swagger `@field:Schema` 가 붙어 있어 그대로 `:service`로 옮기면 `:service`에 `springdoc` 의존이 따라 붙는다. 처리:

- `:service/auth/LoginResult.kt` 신규 생성 (Swagger 무관, 순수 data class). `AuthService`가 이를 반환.
- `:controller/auth/dto/LoginResponse.kt`에 `LoginData`(Swagger 포함)와 `LoginResponse`를 둔다. Controller가 `LoginResult → LoginData` 변환 후 `LoginResponse`로 감싼다.
- 필드는 동일(`accessToken, refreshToken, userId, isNewUser`)하므로 매핑은 1줄.

### 2. `AuthService`가 `infra.kakao.*` 직접 import — 어댑터로 차단

현재 `AuthService`는 `KakaoOAuthClient.getUserInfo()`를 직접 부르고, `KakaoUserResponse`(Kakao 외부 스키마)에서 nickname/profile/phone을 추출한다. 이걸 그대로 두면 `:service`가 `:infra:external`을 봐야 함 → 목표 (B) 위반.

처리:
- `:domain:repository/auth/KakaoAuthPort.kt` 인터페이스 정의: `fun getUserInfo(accessToken: String): KakaoUserInfo`
- `:domain:model/auth/KakaoUserInfo.kt` 도메인 친화 표현 (id, nickname, profileImageUrl, phoneNumber, phoneVerified). `:service`는 이 타입만 본다.
- `:infra:external/kakao/KakaoAuthAdapter.kt`(또는 `KakaoOAuthClient` 자체가 `KakaoAuthPort` 구현) 가 `KakaoUserResponse → KakaoUserInfo` 매핑 + 기존 `KakaoOAuthClient.getUserInfo()` 위임 + 기존 `KakaoTokenInvalidException` / `KakaoServerException` 그대로 throw.

### 3. `AuthController`가 `JwtTokenProvider` 직접 사용 (refresh)

현재 `AuthController.refresh()`가 `JwtTokenProvider`를 직접 호출해 토큰 검증/재발급한다. 새 구조에서:
- `JwtTokenProvider`는 `:core`에 있으므로 `:controller`가 직접 의존해도 모듈 경계 위반은 아님.
- 그대로 유지. **리팩토링 범위 외**: refresh 비즈니스 흐름을 `AuthService`로 옮기는 것은 별도 작업.

### 4. `BusinessException` 계열 — `:domain:model`로 일괄 이동

`BusinessException`이 `ResponseCode` 상수를 참조하므로 `ResponseCode`도 같이 `:domain:model`로 이동한다. `BaseResponse`(HTTP 응답 wrapper)는 별개로 `:controller/common/`에 남는다.

### 5. 기존 `:domain` 모듈 삭제 — `User.kt` 이동

`domain/user/User.kt` (`User` data class + `UserStatus` enum)을 `domain/model/user/User.kt`로 이동한다. 파일 내 KDoc 의 `:infra` 언급은 `:infra:repositoryimpl` 로 갱신한다.

### 6. `infra/repositoryimpl/auth/UserEntity.kt`의 `toDomain()` 유지

현재 `UserEntity.toDomain()`이 이미 정의되어 있으므로 새 위치로 그대로 옮기고, 새로 `User.toEntity()` 확장함수를 추가하여 `UserRepositoryImpl.save()`에서 사용한다.

### 7. 테스트 위치

| 현재 | 새 위치 |
|---|---|
| `app/src/test/kotlin/...auth/AuthKakaoIntegrationTest.kt` | `app/`에 그대로 (스프링 부팅 통합테스트) |
| `app/src/test/kotlin/...api/auth/AuthControllerTest.kt` | `controller/src/test/kotlin/...auth/AuthControllerTest.kt` (`@WebMvcTest` 등 슬라이스 테스트는 모듈 안에서 동작. 부트 컨텍스트가 꼭 필요하다고 확인되면 그때 `app`로 이동) |
| `app/src/test/kotlin/...api/common/exception/GlobalExceptionHandlerTest.kt` | `controller/src/test/kotlin/...common/exception/GlobalExceptionHandlerTest.kt` |
| `core/src/test/kotlin/...hash/PhoneHasherTest.kt` | 그대로 |

## 마이그레이션 순서 (큰 단위)

코드량이 작아 한 번에 가능하지만, 컴파일 단위로 단계 분리하여 각 단계 후 빌드 확인.

1. **모듈 골격 생성** — `settings.gradle.kts` 업데이트, 새 모듈 디렉토리 + 빈 `build.gradle.kts` 생성
2. **`:domain:model` 채우기** — `User.kt`, `UserStatus`, `BusinessException` 계열, `ResponseCode`, `KakaoUserInfo`(신규) 이동/생성
3. **`:domain:repository` 채우기** — `UserRepository`, `KakaoAuthPort` 인터페이스 작성
4. **`:core` 채우기** — `JwtTokenProvider` 이동, JJWT 의존 추가
5. **`:infra:repositoryimpl` 채우기** — `UserEntity`, `UserJpaRepository`, `UserRepositoryImpl`, 매퍼
6. **`:infra:external` 채우기** — Kakao 코드 이동 + `KakaoAuthAdapter` (KakaoAuthPort 구현)
7. **`:service` 채우기** — `AuthService` 이동 + `UserRepository` / `KakaoAuthPort` 의존으로 변경, `LoginResult` 신규 생성
8. **`:controller` 채우기** — `AuthController`, `JwtAuthenticationFilter`, DTO, `BaseResponse`, `GlobalExceptionHandler`, `OpenAPIConfig` 이동, `LoginResult → LoginData` 매핑 추가
9. **`:app` 정리** — 의존성 재배선, 기존 `:api`, `:domain` (단일), `:infra` (단일) 폴더/모듈 삭제
10. **테스트 위치 이동** + 전체 빌드/테스트 통과 확인

각 단계 후 검증:
- `./gradlew :{module}:compileKotlin` 통과
- 단계 9~10 이후 `./gradlew build`, `./gradlew :app:test` 통과
- 의존 격리 검증: `./gradlew :service:dependencies` 출력에 `spring-boot-starter-web`, `spring-data-jpa`, `webflux` 가 **없어야** 한다.

## 비범위 (Out of scope)

- AuthController의 refresh 로직을 AuthService로 이전
- Redis 실제 사용 코드 (현재는 의존성만 있고 코드 없음)
- `:batch` 모듈 내용 추가
- 새 도메인/기능 추가
- 패키지 네임 컨벤션의 광범위한 변경 (`com.lwg.challenge.api.*` 같은 잔여 네임은 새 모듈 이름에 맞춰 모듈별 루트 패키지로 옮기되, 이번 작업 범위는 모듈 이동 + 패키지 prefix 갱신까지만)

## 검증 기준 (Acceptance)

1. `./gradlew build` 성공
2. `./gradlew :app:test` 의 모든 기존 통합 테스트 (`AuthKakaoIntegrationTest`, `AuthControllerTest`, `GlobalExceptionHandlerTest`) 통과
3. `./gradlew :service:dependencies` 결과에 `spring-boot-starter-data-jpa`, `spring-boot-starter-web`, `spring-boot-starter-webflux` 미포함
4. `./gradlew :controller:dependencies` 결과에 `spring-boot-starter-data-jpa`, `spring-boot-starter-webflux` 미포함
5. 기존 `:api`, 단일 `:domain`, 단일 `:infra` 디렉토리 삭제됨
6. 카카오 로그인 통합 테스트가 신규 어댑터 경로(`KakaoAuthPort` → `KakaoOAuthClient`)로 정상 동작
