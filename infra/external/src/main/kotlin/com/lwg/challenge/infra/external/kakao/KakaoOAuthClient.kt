package com.lwg.challenge.infra.external.kakao

import com.lwg.challenge.domain.auth.KakaoServerException
import com.lwg.challenge.domain.auth.KakaoTokenInvalidException
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.util.concurrent.TimeUnit

/**
 * Kakao API 호출 클라이언트.
 *
 * - 호스트: kapi.kakao.com — `/v2/user/me` (로그인), `/v1/user/unlink` (회원탈퇴)
 * - WebClient (Netty) 사용. connect-timeout 2s, read-timeout 5s (yml 기본).
 * - 1회 자동 재시도 (5xx/네트워크 한정, 4xx는 즉시 실패).
 * - 에러 구분:
 *   - 401/403 → [KakaoTokenInvalidException]
 *   - 5xx / 타임아웃 / 네트워크 → [KakaoServerException]
 *
 * `kakao.api-base-url` 은 통합 테스트에서 WireMock URL로 override 한다.
 */
@Component
class KakaoOAuthClient(
    @Value("\${kakao.api-base-url:https://kapi.kakao.com}") private val apiBaseUrl: String,
    @Value("\${kakao.webclient.connect-timeout-ms:2000}") private val connectTimeoutMs: Int,
    @Value("\${kakao.webclient.read-timeout-ms:5000}") private val readTimeoutMs: Int,
    /**
     * 서비스 앱 **어드민 키** — 회원탈퇴 unlink 전용 (mypage T-B2).
     *
     * ⚠️ 기본값이 빈 문자열이고 그게 로컬에서 정상 상태다. 비어 있으면 `KakaoUnlinkConfig` 가
     * `NoOpKakaoUnlinkAdapter` 를 배선하므로 [unlink] 는 **애초에 호출되지 않는다.**
     * 그래서 여기서 빈 값을 검사하지 않는다 — 분기의 주인은 그쪽 한 곳이다.
     */
    @Value("\${kakao.admin-key:}") private val adminKey: String,
) {

    private val log = LoggerFactory.getLogger(KakaoOAuthClient::class.java)

    private val apiWebClient: WebClient by lazy { buildWebClient(apiBaseUrl, "kakao-api") }

    private fun buildWebClient(baseUrl: String, poolName: String): WebClient {
        val connectionProvider = ConnectionProvider.builder(poolName)
            .maxConnections(50)
            .pendingAcquireMaxCount(200)
            .build()

        val httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS))
            }

        return WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }

    fun getUserInfo(accessToken: String): KakaoUserResponse {
        return try {
            doGetUserInfo(accessToken)
        } catch (e: KakaoTokenInvalidException) {
            throw e
        } catch (e: KakaoServerException) {
            log.warn("Kakao /v2/user/me 1차 실패, 1회 재시도. cause={}", e.message)
            try {
                doGetUserInfo(accessToken)
            } catch (retry: Exception) {
                log.warn("Kakao /v2/user/me 재시도 실패. cause={}", retry.message)
                when (retry) {
                    is KakaoTokenInvalidException -> throw retry
                    is KakaoServerException -> throw retry
                    else -> throw KakaoServerException("Kakao /v2/user/me 호출 실패 (재시도 후): ${retry.message}", retry)
                }
            }
        }
    }

    private fun doGetUserInfo(accessToken: String): KakaoUserResponse {
        return try {
            apiWebClient.get()
                .uri("/v2/user/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                .retrieve()
                .onStatus({ status -> status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN }) { resp ->
                    resp.bodyToMono(String::class.java).defaultIfEmpty("").map { body ->
                        KakaoTokenInvalidException("Kakao 토큰 검증 실패: status=${resp.statusCode()} body=$body")
                    }
                }
                .onStatus({ status -> status.is5xxServerError }) { resp ->
                    resp.bodyToMono(String::class.java).defaultIfEmpty("").map { body ->
                        KakaoServerException("Kakao 서버 에러: status=${resp.statusCode()} body=$body")
                    }
                }
                .bodyToMono(KakaoUserResponse::class.java)
                .block() ?: throw KakaoServerException("Kakao 응답 본문이 비어있습니다")
        } catch (e: KakaoTokenInvalidException) {
            throw e
        } catch (e: KakaoServerException) {
            throw e
        } catch (e: WebClientResponseException) {
            if (e.statusCode.is4xxClientError) {
                throw KakaoTokenInvalidException("Kakao 4xx: ${e.statusCode} ${e.responseBodyAsString}", e)
            }
            throw KakaoServerException("Kakao 호출 실패: ${e.message}", e)
        } catch (e: WebClientRequestException) {
            throw KakaoServerException("Kakao 호출 네트워크 오류: ${e.message}", e)
        } catch (e: Exception) {
            val cause = e.cause
            if (cause is KakaoTokenInvalidException) throw cause
            if (cause is KakaoServerException) throw cause
            throw KakaoServerException("Kakao 호출 실패: ${e.message}", e)
        }
    }

    // ─── 회원탈퇴 unlink (mypage T-B2) ───────────────────────────

    /**
     * 우리 앱과 [kakaoId] 계정의 연결을 끊는다 (`POST /v1/user/unlink`).
     *
     * ```
     * Authorization: KakaoAK {adminKey}
     * Content-Type : application/x-www-form-urlencoded;charset=utf-8
     * body         : target_id_type=user_id&target_id={kakaoId}
     * 성공          : 200 {"id": 123456789}
     * ```
     *
     * 재시도 정책은 [getUserInfo] 와 같다 — 5xx/네트워크만 1회, 4xx 는 즉시 실패.
     */
    fun unlink(kakaoId: Long) {
        return try {
            doUnlink(kakaoId)
        } catch (e: KakaoTokenInvalidException) {
            throw e
        } catch (e: KakaoServerException) {
            log.warn("Kakao /v1/user/unlink 1차 실패, 1회 재시도. cause={}", e.message)
            try {
                doUnlink(kakaoId)
            } catch (retry: Exception) {
                log.warn("Kakao /v1/user/unlink 재시도 실패. cause={}", retry.message)
                when (retry) {
                    is KakaoTokenInvalidException -> throw retry
                    is KakaoServerException -> throw retry
                    else -> throw KakaoServerException("Kakao /v1/user/unlink 호출 실패 (재시도 후): ${retry.message}", retry)
                }
            }
        }
    }

    private fun doUnlink(kakaoId: Long) {
        try {
            apiWebClient.post()
                .uri("/v1/user/unlink")
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK $adminKey")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("target_id_type=user_id&target_id=$kakaoId")
                .retrieve()
                .onStatus({ status -> status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN }) { resp ->
                    resp.bodyToMono(String::class.java).defaultIfEmpty("").map { body ->
                        KakaoTokenInvalidException("Kakao unlink 인증 실패(admin key 확인): status=${resp.statusCode()} body=$body")
                    }
                }
                .onStatus({ status -> status.is5xxServerError }) { resp ->
                    resp.bodyToMono(String::class.java).defaultIfEmpty("").map { body ->
                        KakaoServerException("Kakao 서버 에러: status=${resp.statusCode()} body=$body")
                    }
                }
                .bodyToMono(String::class.java)
                .block()
        } catch (e: KakaoTokenInvalidException) {
            throw e
        } catch (e: KakaoServerException) {
            throw e
        } catch (e: WebClientResponseException) {
            // 🔴 여기가 unlink 만의 분기다. 아래 [isNotLinked] KDoc 참조.
            if (isNotLinked(e)) {
                log.info("Kakao unlink: 이미 연결이 없는 계정이다(code=-101). 성공으로 처리한다. kakaoId={}", kakaoId)
                return
            }
            if (e.statusCode.is4xxClientError) {
                throw KakaoTokenInvalidException("Kakao unlink 4xx: ${e.statusCode} ${e.responseBodyAsString}", e)
            }
            throw KakaoServerException("Kakao unlink 실패: ${e.message}", e)
        } catch (e: WebClientRequestException) {
            throw KakaoServerException("Kakao unlink 네트워크 오류: ${e.message}", e)
        } catch (e: Exception) {
            val cause = e.cause
            if (cause is KakaoTokenInvalidException) throw cause
            if (cause is KakaoServerException) throw cause
            if (cause is WebClientResponseException && isNotLinked(cause)) {
                log.info("Kakao unlink: 이미 연결이 없는 계정이다(code=-101). 성공으로 처리한다. kakaoId={}", kakaoId)
                return
            }
            throw KakaoServerException("Kakao unlink 실패: ${e.message}", e)
        }
    }

    /**
     * *"연결되지 않은 사용자"* (카카오 에러 코드 `-101`) 인가.
     *
     * ## 🔴 이것만 4xx 중 유일하게 **성공**이다
     *
     * `-101` 은 **이미 원하는 상태**를 뜻한다. 탈퇴는 재시도될 수 있는 연산이고(unlink 는 됐는데
     * DB 커밋이 실패한 경우가 실재한다 — `WithdrawalService` 의 실행 순서 참조), 여기서 실패로
     * 답하면 그 사용자는 **영원히 탈퇴하지 못한다.**
     *
     * ⚠️ **HTTP status 로는 구분되지 않는다** — `-101` 은 HTTP **400** 으로 온다. 그래서 body 를
     * 봐야 하고, 그게 이 함수가 존재하는 유일한 이유다.
     *
     * body 전체를 DTO 로 역직렬화하지 않고 정규식으로 한 필드만 보는 이유: 게이트웨이가 끼면
     * 카카오 JSON 이 아닌 HTML/빈 본문이 올 수 있는데, 그때 파서가 던지면 **판정 자체가
     * 예외로 바뀐다.** 문자열 매칭은 그런 입력에서 조용히 false 가 된다 — 여기서는 그쪽이 옳다.
     */
    private fun isNotLinked(e: WebClientResponseException): Boolean =
        e.statusCode == HttpStatus.BAD_REQUEST && NOT_LINKED_CODE.containsMatchIn(e.responseBodyAsString)

    private companion object {
        /** `{"msg":"...","code":-101}` — 공백 변형만 허용하고 그 외에는 매칭하지 않는다. */
        val NOT_LINKED_CODE = Regex(""""code"\s*:\s*-101\b""")
    }
}
