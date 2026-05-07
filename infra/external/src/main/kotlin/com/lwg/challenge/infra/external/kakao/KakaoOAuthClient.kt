package com.lwg.challenge.infra.external.kakao

import com.lwg.challenge.domain.auth.KakaoServerException
import com.lwg.challenge.domain.auth.KakaoTokenInvalidException
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.util.concurrent.TimeUnit

/**
 * Kakao API 호출 클라이언트 (Kakao SDK access_token 검증 전용).
 *
 * - 호스트: kapi.kakao.com — `/v2/user/me`
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
}
