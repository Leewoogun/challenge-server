---
name: module-conventions
description: challenge-server 멀티모듈 프로젝트에서 어떤 코드를 어느 모듈에 두어야 하는지 규정한다. 새 클래스/파일을 만들기 전, 기존 파일을 다른 모듈로 옮기기 전, build.gradle.kts 의존을 추가하기 전 반드시 이 스킬을 참조한다. "어디에 둬야 하나", "어느 모듈에 만들지" 라는 판단이 필요한 모든 상황에서 사용.
---

# Module Conventions — 모듈별 책임 분담

challenge-server 의 13개 모듈 각각이 무엇을 담당하는지, 어떤 클래스를 어디에 두어야 하는지 정한다.

## 모듈 지도

```
app                            — Spring Boot 진입점 (@SpringBootApplication, SecurityConfig, db/migration/*.sql)
├─ controller                  — @RestController, DTO, OpenAPI, GlobalExceptionHandler, JwtAuthenticationFilter
├─ service                     — @Service 비즈니스 로직, @Transactional
├─ batch                       — Spring Batch 잡
├─ domain
│  ├─ model                    — 순수 Kotlin 도메인 객체, BusinessException, ResponseCode
│  └─ repository               — 도메인 포트 인터페이스 (XxxRepository, XxxPort)
├─ infra
│  ├─ entity                   — JPA @Entity, toDomain/fromDomain
│  ├─ jpa                      — JpaRepository<E, ID> 인터페이스
│  ├─ repositoryimpl           — domain/repository 의 JPA 구현체 (@Component)
│  └─ external                 — 외부 API 어댑터 (@Component)
├─ core                        — 공통 유틸 (JWT, 해시, 암호화 등) — Spring 의존 X 권장
└─ build-logic                 — Gradle convention plugins
```

## 새 클래스 위치 결정 트리

```
새 클래스를 어디에 두지?
│
├─ 비즈니스 의미를 가진 모델/값 (User, Challenge, ChallengeStatus enum)?
│  → domain/model
│
├─ 비즈니스 예외 (BusinessException 하위)?
│  → domain/model/.../common/exception/  또는  domain/model/.../<aggregate>/
│
├─ 외부 시스템(DB, Kakao, FCM, ...) 호출 인터페이스 (포트)?
│  → domain/repository
│
├─ JPA 엔티티 클래스 (@Entity)?
│  → infra/entity
│
├─ JpaRepository<E, ID> 인터페이스?
│  → infra/jpa
│
├─ 도메인 포트의 JPA 구현체 (@Component, toDomain/fromDomain 호출)?
│  → infra/repositoryimpl
│
├─ 외부 API 어댑터 (@Component, 도메인 포트 구현, WebClient/RestTemplate 사용)?
│  → infra/external
│
├─ 비즈니스 로직 (@Service, @Transactional, 여러 포트 조합)?
│  → service
│
├─ REST 엔드포인트 (@RestController) / Request/Response DTO / OpenAPI annotations?
│  → controller
│
├─ JWT, Hash, 암호화, 시간 유틸 등 도메인-중립 + 다수 모듈 공유?
│  → core
│
├─ Spring Boot 진입점, SecurityConfig, application.yml, Flyway SQL?
│  → app
│
└─ Spring Batch Job / Tasklet?
   → batch
```

## 패키지 컨벤션

모든 코드는 `com.lwg.challenge` 하위.

```
{module-package}.{aggregate}[.{subcategory}]
```

예시:
- `com.lwg.challenge.domain.user.User`
- `com.lwg.challenge.domain.auth.KakaoAuthPort`
- `com.lwg.challenge.domain.common.exception.BusinessException`
- `com.lwg.challenge.service.auth.AuthService`
- `com.lwg.challenge.controller.auth.AuthController`
- `com.lwg.challenge.controller.auth.dto.LoginRequest`
- `com.lwg.challenge.controller.common.BaseResponse`
- `com.lwg.challenge.controller.common.exception.GlobalExceptionHandler`
- `com.lwg.challenge.infra.entity.auth.UserEntity`
- `com.lwg.challenge.infra.jpa.auth.UserJpaRepository`
- `com.lwg.challenge.infra.repositoryimpl.auth.UserRepositoryImpl`
- `com.lwg.challenge.infra.external.kakao.KakaoAuthAdapter`
- `com.lwg.challenge.core.auth.JwtTokenProvider`
- `com.lwg.challenge.core.hash.PhoneHasher`

## 모듈 의존 규칙 요약

| 작성 모듈 | 의존 가능 (`implementation` 또는 `api`) |
|---------|--------------------------------------|
| `domain/model` | `core` |
| `domain/repository` | `core`, `domain/model` (api 로 노출) |
| `service` | `core`, `domain/model` (간접), `domain/repository` |
| `controller` | `core`, `domain/model` (간접), `domain/repository`, `service`, `springdoc-openapi`, spring-web/security/validation |
| `infra/entity` | `core`, `domain/model` (api — toDomain 시그니처에 노출) |
| `infra/jpa` | `core`, `infra/entity` (api), spring-data-jpa (api) |
| `infra/repositoryimpl` | `core`, `domain/repository`, `infra/entity`, `infra/jpa` |
| `infra/external` | `core`, `domain/repository`, spring-webflux, spring-data-redis |
| `app` | 모든 모듈 |
| `batch` | `core`, `domain/repository` (필요 시), spring-batch |

> **api vs implementation 결정 규칙**: 노출하는 클래스가 다른 모듈의 컴파일러에 필요(메소드 시그니처에 등장)하면 `api`, 내부 구현에서만 쓰면 `implementation`. 잘못 쓰면 빌드는 통과하나 컴파일 시간이 늘거나 IDE 가 import 를 못 찾는다.

## 자주 하는 실수

| 잘못된 위치 | 올바른 위치 | 이유 |
|----------|----------|------|
| `service` 에 RestController | `controller` | 프레젠테이션과 비즈니스 분리 |
| `controller` 에 @Transactional | `service` | 트랜잭션은 비즈니스 경계 |
| `domain/model` 에 @Entity | `infra/entity` | 도메인은 영속화 무지 |
| `domain/repository` 에 JpaRepository 상속 | `infra/jpa` | 포트는 도메인 언어, JpaRepository 는 인프라 |
| `infra/repositoryimpl` 에 비즈니스 로직 | `service` | 어댑터는 변환만 |
| `infra/external` 에서 도메인 예외 변환 안 함 (그대로 throw HttpClientException) | 외부 예외 → 도메인 예외 변환 후 throw | 서비스가 인프라 예외를 모르도록 |
| `controller` 에서 Entity 직접 반환 | DTO 로 감싸 BaseResponse 상속 | 영속 모델 누설 방지 |
| 새 마이그레이션 V1__init.sql 수정 | 새 V{N}__xxx.sql 추가 | 머지된 마이그레이션은 불변 |

## 새 모듈을 추가할 때

거의 없겠지만, 추가가 필요하면:

1. `settings.gradle.kts` 에 `include(":<path>:<name>")` 추가
2. 디렉토리 + `build.gradle.kts` 생성 (기존 모듈을 템플릿으로)
3. 적절한 convention plugin 적용 (`challenge.spring.library` 또는 `challenge.spring.boot` 또는 `challenge.spring.jpa`)
4. `app/build.gradle.kts` 에 `implementation(projects.<path>.<name>)` 추가
5. 의존 가능 표를 업데이트하고 architecture-guard 에 알림
