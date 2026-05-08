---
name: kotlin-spring-style
description: challenge-server 에서 Kotlin + Spring Boot 코드를 작성할 때의 스타일/관용구 규칙을 정의한다. 클래스 선언(class vs data class), 의존 주입, @Service/@Transactional 위치, JPA Entity 작성, Logger 패턴, Null 처리, 불변성 등 코드 작성 시 반드시 참조한다.
---

# Kotlin + Spring 코드 스타일

challenge-server 의 코드 컨벤션. Spring Boot 3.x + Kotlin 1.9+ + JPA + Spring Security 환경.

## 클래스 선언

### data class
- 도메인 모델, DTO, 결과 객체 (LoginResult 같은) → **data class**
- equals/hashCode/copy/componentN 자동 생성 + 의도가 "값 객체" 임을 표현

```kotlin
data class User(
    val id: Long?,
    val kakaoId: Long,
    val nickname: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
```

### 일반 class
- JPA Entity → **일반 class** (kotlin-jpa 플러그인이 no-arg 생성자 생성, 필드는 var)
- @Service, @Component, @RestController 등 Spring 빈 → **일반 class** (생성자 주입)

## 의존 주입

**생성자 주입 + private val** 만 사용. `@Autowired` 필드/세터 주입 금지.

```kotlin
@Service
class AuthService(
    private val kakaoAuthPort: KakaoAuthPort,
    private val userRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) { ... }
```

## @Transactional

- **메소드에 부착**, 클래스 단위 X (의도가 명확하지 않은 트랜잭션이 퍼지는 것 방지)
- 쓰기: `@Transactional`
- 읽기 전용: `@Transactional(readOnly = true)`
- 트랜잭션 import: `org.springframework.transaction.annotation.Transactional` (jakarta 아님)

## Logger

각 클래스 내부에 다음 패턴:

```kotlin
private val log = LoggerFactory.getLogger(<ClassName>::class.java)
```

레벨 가이드:
- `log.debug(...)` — 비즈니스 예외 (자주 발생, 의도된 흐름)
- `log.info(...)` — 사용자 액션 / 주요 이벤트 (로그인 성공 등)
- `log.warn(...)` — 외부 시스템 에러 (5xx, 타임아웃)
- `log.error(...)` — 예상치 못한 예외, 서비스 오작동

## Null 처리

- 도메인 모델의 `id: Long?` 패턴: 신규 객체는 null, 영속 후 채워짐
- safe-call(`?.`) + Elvis(`?:`) 적극 사용
- `!!` 는 진짜로 null 일 수 없는 곳 (DB 저장 직후 ID 등) 에서만 + 의도가 분명할 때
- 더 명확하게는 `error("...")` 로 메시지와 함께:
  ```kotlin
  val userId: Long = saved.id ?: error("saved user id is null")
  ```

## 불변성

- 도메인 모델 / DTO: `val` only, 변경은 `copy(...)` 로
- JPA Entity: `var` (JPA 가 필드 채워야 하므로)
- 상수: `const val` (companion object 또는 top-level)

## JPA Entity 작성 패턴

```kotlin
@Entity
@Table(name = "users")
class UserEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "kakao_id", nullable = false, unique = true)
    var kakaoId: Long,

    @Column(name = "nickname", nullable = false, length = 50)
    var nickname: String,

    // ...

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {

    @PrePersist
    fun prePersist() {
        val now = LocalDateTime.now()
        if (createdAt == LocalDateTime.MIN) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = LocalDateTime.now()
    }

    fun toDomain(): User = User(
        id = id, kakaoId = kakaoId, nickname = nickname,
        createdAt = createdAt, updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(user: User): UserEntity = UserEntity(
            id = user.id, kakaoId = user.kakaoId, nickname = user.nickname,
            createdAt = user.createdAt, updatedAt = user.updatedAt,
        )
    }
}
```

체크리스트:
- 모든 필드 `var`
- `@Id @GeneratedValue(IDENTITY) var id: Long? = null`
- 모든 컬럼에 `@Column(name = "snake_case", ...)` 명시
- 시간 필드에 `@PrePersist`, `@PreUpdate`
- `toDomain()` 인스턴스 메소드 + `fromDomain()` companion object
- `@Repository`, `@Service` 같은 Spring 빈 어노테이션 X (Entity 는 빈이 아님)

## REST Controller 작성 패턴

```kotlin
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "인증 엔드포인트")
class AuthController(
    private val authService: AuthService,
    private val jwtTokenProvider: JwtTokenProvider,
) {

    @PostMapping("/kakao")
    @Operation(
        summary = "카카오 로그인",
        description = "...",
    )
    fun kakaoLogin(@Valid @RequestBody request: KakaoLoginRequest): LoginResponse {
        val result = authService.loginWithKakao(request.kakaoAccessToken)
        return LoginResponse(data = LoginData(...))
    }
}
```

체크리스트:
- `@RestController @RequestMapping("/api/v1/<resource>")` (버전 prefix 필수)
- 클래스에 `@Tag(name, description)`
- 각 메소드에 `@Operation(summary, description)`
- 인증 필요 시 `@SecurityRequirement(name = "bearerAuth")`
- 요청에 `@Valid @RequestBody`
- 반환은 BaseResponse 상속 DTO 직접 (ResponseEntity 감싸지 말 것 — GlobalExceptionHandler 외에는)
- try/catch 금지

## DTO 패턴

```kotlin
// 요청
data class KakaoLoginRequest(
    @field:NotBlank(message = "카카오 access token 이 필요합니다")
    val kakaoAccessToken: String,
)

// 응답: BaseResponse 상속 + nested data
data class LoginData(
    val accessToken: String,
    val refreshToken: String,
    val userId: Long,
    val isNewUser: Boolean,
)

class LoginResponse(val data: LoginData) : BaseResponse()
```

특이점:
- jakarta validation 어노테이션은 `@field:` prefix 필수 (data class 기본 생성자 인자)
- 응답은 `BaseResponse(error=false, code=200, message="")` 가 자동, `data` 만 채움

## 코틀린 관용구

| 상황 | 권장 패턴 |
|------|---------|
| null 일 때 default | `value ?: default` |
| 비어 있지 않을 때 변환 | `s.takeIf { it.isNotBlank() }?.let { transform(it) }` |
| 시그니처를 줄이기 | named arguments 적극 사용 |
| 쌍 반환 | `Pair` 또는 destructuring (`val (a, b) = ...`) |
| 외부 예외 매핑 | `inline fun <T> mapXyz(action: () -> T): T = try { action() } catch (...)` 헬퍼 |
| companion object 상수 | `companion object { private const val MAX_LENGTH = 50 }` |

## Import 정리

- wildcard import (`import xxx.*`) 금지
- 사용하지 않는 import 제거 (IDE optimize imports)

## 한국어 주석 / 메시지

- KDoc 주석은 한국어로 (기존 코드와 일관)
- 사용자에게 노출되는 에러 메시지도 한국어
- 변수/함수명은 영어

## 금지 패턴

| 금지 | 이유 |
|------|------|
| `@Autowired` 필드/세터 주입 | 테스트 어렵고 final 보장 안 됨 |
| 클래스 단위 `@Transactional` | 의도 불명확 |
| `lateinit var` (Spring 빈에서) | 생성자 주입으로 대체 가능 |
| `!!` 남발 | NPE 위험 |
| `print` / `println` | log 사용 |
| 컨트롤러 try/catch | GlobalExceptionHandler 위임 |
| Entity 를 controller 에서 반환 | 영속 모델 누설 |
