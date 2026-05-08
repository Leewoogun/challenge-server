---
name: dip-architecture
description: challenge-server 의 헥사고날 아키텍처 + DIP(의존 역전 원칙) 규칙을 정의한다. 도메인 포트 인터페이스 작성, 인프라 어댑터 구현, 외부 시스템 연동(카카오/FCM/Redis 등) 추가, 모듈 간 의존 방향 점검에 모두 사용. "포트 추가", "어댑터 구현", "외부 API 연동", "DIP 위반인지 확인" 같은 요청 시 반드시 이 스킬을 참조한다.
---

# DIP & Hexagonal Architecture

challenge-server 는 헥사고날 (포트와 어댑터) + DIP 를 따른다. 모든 외부 시스템 의존은 도메인이 정의한 포트 인터페이스를 통해 역전된다.

## 핵심 원칙

1. **도메인은 외부를 모른다.** `:domain:model`, `:domain:repository` 는 어떤 DB/HTTP 클라이언트/Spring 어노테이션도 import 하지 않는다.
2. **포트는 도메인이 정의, 어댑터는 인프라가 구현.** 포트 인터페이스는 `:domain:repository` 에 위치하고, 그 구현체는 `:infra:repositoryimpl` 또는 `:infra:external` 에 위치한다.
3. **의존은 안쪽으로만.** 화살표는 항상 controller → service → domain 방향. 그 반대는 절대 X.
4. **포트 시그니처는 도메인 언어로 표현.** `findByEmail` (O), `selectFromUsersWhereEmail` (X).

## 의존 방향 도식

```
[app]
 ↓
[controller] → [service] → [domain.repository (port)]
                                    ↑ implements
                          [infra.repositoryimpl (adapter)]
                          [infra.external (adapter)]
                                    ↓
                          [infra.jpa] → [infra.entity] → [domain.model]
                                                            ↑
                                                       [domain.repository]
```

화살표 = "의존한다 / import 한다". `infra/repositoryimpl` 이 `domain/repository` 의 인터페이스를 implements 하므로 컴파일 의존이 생기지만, 의미적으로는 "도메인이 추상에 의존하고 인프라가 구체에 의존" 하는 DIP 가 성립한다.

## 포트 작성 가이드

### DB 포트 (CRUD 위주)

```kotlin
// :domain:repository
package com.lwg.challenge.domain.user

import com.lwg.challenge.domain.user.User

interface UserRepository {
    fun findByKakaoId(kakaoId: Long): User?
    fun save(user: User): User
}
```

특징:
- 메소드명은 도메인 행위
- 반환 타입은 도메인 모델 (또는 nullable / 컬렉션)
- Spring/JPA 어노테이션 없음
- 예외 계약은 KDoc 으로

### 외부 시스템 포트 (HTTP API 등)

```kotlin
// :domain:repository
package com.lwg.challenge.domain.auth

interface KakaoAuthPort {
    /**
     * @throws KakaoTokenInvalidException 토큰 만료/유효하지 않음
     * @throws KakaoServerException 5xx / 타임아웃 / 재시도 후에도 실패
     */
    fun getUserInfo(accessToken: String): KakaoUserInfo
}
```

특징:
- 외부 시스템의 raw shape 을 노출하지 않는다 (KakaoUserResponse 가 아닌 KakaoUserInfo 도메인 객체)
- 외부 예외도 도메인 예외로 표현 (KakaoTokenInvalidException 은 :domain:model 에 정의)
- KDoc 에 예외 계약 명시

## 어댑터 작성 가이드

### DB 어댑터 (`:infra:repositoryimpl`)

```kotlin
@Component
class UserRepositoryImpl(
    private val jpa: UserJpaRepository,
) : UserRepository {

    override fun findByKakaoId(kakaoId: Long): User? =
        jpa.findByKakaoId(kakaoId)?.toDomain()

    override fun save(user: User): User =
        jpa.save(UserEntity.fromDomain(user)).toDomain()
}
```

원칙:
- `@Component` (NOT `@Repository` — Spring Data JPA 가 이미 jpa 인터페이스를 등록)
- 비즈니스 로직 X — 단순 변환과 위임만
- 변환 책임은 Entity 의 toDomain/fromDomain

### 외부 API 어댑터 (`:infra:external`)

```kotlin
@Component
class KakaoAuthAdapter(
    private val client: KakaoOAuthClient,
) : KakaoAuthPort {

    override fun getUserInfo(accessToken: String): KakaoUserInfo {
        val raw: KakaoUserResponse = try {
            client.fetchMe(accessToken)
        } catch (e: WebClientResponseException.Unauthorized) {
            throw KakaoTokenInvalidException("token invalid")
        } catch (e: WebClientResponseException) {
            if (e.statusCode.is5xxServerError) {
                throw KakaoServerException("kakao 5xx: ${e.statusCode}")
            }
            throw KakaoServerException("kakao error: ${e.statusCode}")
        }
        return raw.toDomain()
    }
}
```

원칙:
- 외부 예외 → 도메인 예외 변환은 어댑터의 책임
- raw response 객체는 어댑터/클라이언트 내부에만 존재
- 1회 재시도 같은 인프라 정책도 어댑터 안

## 자주 하는 DIP 위반

| 안티패턴 | 왜 잘못됐나 | 수정 |
|---------|-----------|------|
| service 에서 `JpaRepository<UserEntity, Long>` 직접 주입 | 서비스가 인프라(JPA)를 알게 됨 | 도메인 포트 (`UserRepository`) 를 주입 |
| domain/model 에 `@Entity` 어노테이션 | 도메인이 영속화 기술을 알게 됨 | UserEntity 를 별도로 infra/entity 에 만들고 toDomain/fromDomain 변환 |
| domain/repository 에 `JpaRepository` 상속 | 포트가 인프라 슈퍼타입을 노출 | 순수 interface 로 정의, JpaRepository 는 :infra:jpa 에 별도로 |
| controller 에서 UserEntity 반환 | 영속 모델이 외부로 누설 + JPA lazy loading 이슈 | DTO 로 변환 (BaseResponse 상속) |
| service 에서 외부 HTTP 클라이언트 (`WebClient`) 직접 사용 | 비즈니스가 인프라 디테일에 결합 | 도메인 포트 (`XxxPort`) 정의 후 :infra:external 에 어댑터 |
| infra/external 어댑터가 외부 예외 그대로 throw | 서비스가 `WebClientResponseException` 같은 인프라 예외를 catch 해야 함 | 어댑터에서 도메인 예외 (`KakaoTokenInvalidException` 등) 로 변환 |

## DIP 위반 점검 명령어

```bash
# domain/model 에 Spring/JPA import 가 있나?
grep -r "import org.springframework\|import jakarta.persistence\|import com.fasterxml.jackson" \
  domain/model/src/main/kotlin

# domain/repository 에 같은 위반?
grep -r "import org.springframework\|import jakarta.persistence" \
  domain/repository/src/main/kotlin

# service 가 infra 를 import 하나?
grep -r "import com.lwg.challenge.infra" service/src/main/kotlin

# build.gradle.kts 의 의존 방향 점검
grep -A 20 "^dependencies" service/build.gradle.kts
grep -A 20 "^dependencies" domain/repository/build.gradle.kts
```

위 결과가 비어 있어야 정상.

## 새 외부 시스템 연동 절차 (예: FCM)

1. **포트 정의** (`:domain:repository`)
   ```kotlin
   interface FcmPort {
       fun send(token: String, title: String, body: String)
   }
   ```
2. **도메인 예외 정의** (`:domain:model`)
   ```kotlin
   class FcmInvalidTokenException(message: String) : RuntimeException(message)
   ```
3. **어댑터 구현** (`:infra:external`)
   ```kotlin
   @Component
   class FcmAdapter(...) : FcmPort { override fun send(...) { ... } }
   ```
4. **서비스에서 사용** (`:service`)
   ```kotlin
   @Service
   class NotificationService(private val fcm: FcmPort) { ... }
   ```
5. **의존 추가**: `infra/external/build.gradle.kts` 에 firebase-admin 라이브러리만 추가. service 의 build.gradle.kts 는 변경 없음.

## 헥사고날의 이득 (왜 이렇게 하나)

- **테스트 용이성**: 서비스 테스트에서 포트만 mock 하면 외부 의존 없이 동작 검증.
- **인프라 교체**: JPA → MyBatis, Kakao → Naver 로 바꿔도 도메인/서비스 무수정.
- **모듈 빌드 시간**: 인프라 라이브러리(JPA, WebClient) 가 도메인 모듈 컴파일에 필요 없어 빨라짐.
- **새 어댑터 추가가 안전**: 도메인 포트 시그니처가 컨트랙트, 어댑터는 그것만 만족하면 됨.
