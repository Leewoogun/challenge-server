---
name: api-response-convention
description: challenge-server 의 API 응답 형식(BaseResponse), 에러 코드(ResponseCode), 비즈니스 예외 분류(BusinessException 하위), GlobalExceptionHandler 동작 방식, OpenAPI 어노테이션 사용을 정의한다. 모든 컨트롤러/DTO/예외 클래스를 작성할 때 반드시 참조한다.
---

# API 응답 / 예외 컨벤션

challenge-server 의 모든 API 응답은 통일된 형식과 에러 코드 체계를 따른다. 모바일 클라이언트(`:remote:datasource` 의 `ApiCode`) 와 1:1 매칭되어야 한다.

## 응답 형식

모든 응답은 `BaseResponse` 를 상속한다.

```kotlin
open class BaseResponse(
    val error: Boolean = false,
    val code: Int = 200,
    val message: String = "",
)
```

### 성공 응답

데이터가 없는 경우:
```kotlin
@DeleteMapping("/logout")
fun logout(): BaseResponse {
    return BaseResponse()  // error=false, code=200, message=""
}
```

데이터가 있는 경우 — `BaseResponse` 상속 + nested data:
```kotlin
data class LoginData(
    val accessToken: String,
    val refreshToken: String,
    val userId: Long,
    val isNewUser: Boolean,
)

class LoginResponse(val data: LoginData) : BaseResponse()
```

JSON 결과:
```json
{ "error": false, "code": 200, "message": "",
  "data": { "accessToken": "...", "refreshToken": "...", "userId": 1, "isNewUser": false } }
```

### 에러 응답

컨트롤러에서 직접 에러 응답을 만들지 않는다. `BusinessException` 을 throw 하면 `GlobalExceptionHandler` 가 자동 변환한다.

```json
{ "error": true, "code": 701, "message": "카카오 로그인이 만료되었습니다." }
```

## ResponseCode

```kotlin
object ResponseCode {
    const val SUCCESS = 200
    const val UNAUTHORIZED = 401
    const val INTERNAL_ERROR = 500

    const val SNACKBAR_ERROR = 700              // 토스트
    const val DIALOG_ERROR = 701                // 확인 다이얼로그
    const val FULL_SCREEN_ERROR_A = 702         // 전체화면 에러 (재시도 가능)
    const val FULL_SCREEN_ERROR_B = 703         // 전체화면 에러 (인프라 장애)
    const val ONE_BUTTON_DIALOG_ERROR = 705     // 단일 버튼 다이얼로그
}
```

## BusinessException 하위

| 예외 | code | UI 표현 | 사용 시점 |
|------|-----|--------|---------|
| `SnackbarException` | 700 | 토스트 / 스낵바 | 가장 흔한 에러. 입력 검증 실패, 권한 없음, 단순 안내 |
| `DialogException` | 701 | 확인 다이얼로그 | 사용자 확인이 필요한 에러 (토큰 만료 → 다시 로그인 안내 등) |
| `FullScreenException` | 702 / 703 | 전체화면 에러 | 화면 전체가 사용 불가능한 상태 (인프라 장애) |
| `OneButtonDialogException` | 705 | 단일 버튼 다이얼로그 | 강제로 종료/이동시켜야 하는 상황 (강제 업데이트 등) |
| `UnauthorizedException` | 401 | 모바일 Ktor Authenticator 가 401 트리거로 refresh 호출 후 자동 재시도 | JWT 만료/무효. **HTTP status 도 401** |

## 어떤 예외를 던질지 선택 가이드

```
이 에러를 사용자가 보면 어떻게 해야 하지?
│
├─ "잠깐 안내만 하고 사용자가 계속 진행해도 된다"
│  → SnackbarException("닉네임은 50자 이내여야 합니다")
│
├─ "사용자에게 명시적 확인이 필요하다 (다시 로그인 등)"
│  → DialogException("카카오 로그인이 만료되었습니다. 다시 시도해주세요")
│
├─ "재시도하면 될 일시적 인프라 장애"
│  → FullScreenException(ResponseCode.FULL_SCREEN_ERROR_A, "잠시 후 다시 시도해주세요")
│
├─ "복구 불가능한 인프라 장애 (외부 5xx, DB down 등)"
│  → FullScreenException(ResponseCode.FULL_SCREEN_ERROR_B, "일시적인 장애로 ...")
│
├─ "강제 업데이트 / 강제 로그아웃 등 단일 액션만 가능"
│  → OneButtonDialogException("최신 버전으로 업데이트가 필요합니다")
│
└─ "JWT 토큰이 만료되었거나 유효하지 않다"
   → UnauthorizedException()  // 모바일이 refresh 후 재시도
```

## 외부 시스템 예외 변환

외부 API (카카오 등) 가 던지는 예외는 인프라 어댑터에서 도메인 예외로 1차 변환, 서비스에서 BusinessException 으로 2차 변환한다.

### 1차: 어댑터 (`:infra:external`)
인프라 예외 → 도메인 예외 (KakaoTokenInvalidException, KakaoServerException — `:domain:model` 에 정의):

```kotlin
override fun getUserInfo(accessToken: String): KakaoUserInfo {
    try {
        return client.fetchMe(accessToken).toDomain()
    } catch (e: WebClientResponseException.Unauthorized) {
        throw KakaoTokenInvalidException("token invalid")
    } catch (e: WebClientResponseException) {
        if (e.statusCode.is5xxServerError) throw KakaoServerException("kakao 5xx")
        throw KakaoServerException("kakao error")
    }
}
```

### 2차: 서비스 (`:service`)
도메인 외부 예외 → BusinessException:

```kotlin
private inline fun <T> mapKakaoExceptions(stage: String, action: () -> T): T = try {
    action()
} catch (e: KakaoTokenInvalidException) {
    log.info("Kakao {} 실패 (token invalid): {}", stage, e.message)
    throw DialogException("카카오 로그인이 만료되었습니다. 다시 시도해주세요")
} catch (e: KakaoServerException) {
    log.warn("Kakao {} 실패 (server): {}", stage, e.message)
    throw FullScreenException(ResponseCode.FULL_SCREEN_ERROR_B, "일시적인 장애로 로그인할 수 없습니다")
}
```

이렇게 분리하는 이유:
- 어댑터는 "인프라 raw 예외" → "도메인 추상 예외" (서비스가 인프라 종속을 모름)
- 서비스는 도메인 예외 → 사용자 표현용 BusinessException (UI 분류 결정)

## GlobalExceptionHandler 동작

```kotlin
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<BaseResponse> {
        val status = if (e.code in 400..599) HttpStatus.valueOf(e.code) else HttpStatus.OK
        return ResponseEntity.status(status).body(
            BaseResponse(error = true, code = e.code, message = e.message ?: "")
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: ...): ResponseEntity<BaseResponse> =
        ResponseEntity.ok(BaseResponse(error = true, code = SNACKBAR_ERROR, message = firstFieldError))

    @ExceptionHandler(Exception::class)
    fun handleUncaught(e: Exception): ResponseEntity<BaseResponse> =
        ResponseEntity.status(500).body(BaseResponse(error = true, code = INTERNAL_ERROR, message = "서버 오류가 발생했습니다"))
}
```

핵심:
- **HTTP 표준 범위 코드(400~599)** → HTTP status 그대로 + body.code 동일
  - `UnauthorizedException(401)` → HTTP **401** + body.code=401 (모바일 Ktor Authenticator 가 401 을 트리거로 refresh 진입)
  - 5xx 도 동일 — HTTP status 와 body.code 일치
- **700번대 비즈니스 코드(UI 분류)** → HTTP **200** + body.code=7xx
  - Snackbar/Dialog/FullScreen/OneButtonDialog 는 전송 실패가 아닌 UI 분기용. HTTP status 로 노출하면 미들웨어가 끼어듦
- Validation 실패 → HTTP **200** + 첫 번째 필드 에러 메시지로 SnackbarException(700) 처리
- 핸들러로 잡히지 않은 예외 → HTTP **500** + INTERNAL_ERROR

> **두 갈래로 나누는 이유**: 인증 만료처럼 표준 HTTP 의미를 갖는 에러는 HTTP status 로 내려야 클라이언트 미들웨어(Ktor Authenticator, Retrofit Authenticator 등) 가 refresh 흐름을 자동 처리할 수 있다. 반대로 700번대는 UI 표현 분류일 뿐이라 HTTP status 로 노출하면 캐시/로깅/리트라이 미들웨어가 잘못 반응할 수 있어 200 으로 가둔다.

## OpenAPI 어노테이션

### 컨트롤러 클래스
```kotlin
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "인증 엔드포인트")
class AuthController(...)
```

### 엔드포인트
```kotlin
@PostMapping("/kakao")
@Operation(
    summary = "카카오 로그인",
    description = "모바일이 Kakao SDK 로 획득한 access_token 을 받아 ...",
)
fun kakaoLogin(@Valid @RequestBody request: KakaoLoginRequest): LoginResponse { ... }
```

### 인증 필요 엔드포인트
```kotlin
@DeleteMapping("/logout")
@Operation(summary = "로그아웃", description = "...")
@SecurityRequirement(name = "bearerAuth")
fun logout(): BaseResponse { ... }
```

> `bearerAuth` 정의는 `:controller` 의 `OpenAPIConfig` 에 있다.

## 자주 하는 실수

| 실수 | 수정 |
|------|------|
| 컨트롤러에서 `try/catch` | 위임 — BusinessException throw, GlobalExceptionHandler 가 처리 |
| 700번대 비즈니스 코드를 HTTP 4xx 로 반환 | 700번대는 HTTP 200 + body.code. (4xx/5xx 표준 코드만 HTTP status 로 내림) |
| `RuntimeException("...")` 직접 throw | BusinessException 하위 중 적합한 것 선택 (SnackbarException 등) |
| 응답 DTO 가 BaseResponse 상속 안 함 | 무조건 상속. 모바일이 통일된 파싱 로직 사용 |
| `data` 필드를 `Map<String, Any>` 로 | 명시적 data class — 타입 안전성 + OpenAPI 자동 문서화 |
| validation 어노테이션을 일반 prefix 로 | `@field:NotBlank(...)` 처럼 `field:` prefix 필수 (Kotlin data class 기본 생성자) |
| 인증 필요 endpoint 에 `@SecurityRequirement` 누락 | OpenAPI 문서에서 자물쇠 아이콘 안 나옴 — 명시 필수 |
| 외부 5xx 를 그대로 SnackbarException 으로 | FullScreenException(FULL_SCREEN_ERROR_B) 가 의미상 정확 |
