package com.lwg.challenge.api.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * SpringDoc OpenAPI 구성. Bearer JWT 스킴.
 * 로컬에서 http://localhost:8080/swagger-ui/index.html 로 접근.
 *
 * 위치: :api 모듈 (swagger-models 의존성이 있는 곳). @Configuration이므로
 * :app의 @SpringBootApplication 컴포넌트 스캔에 의해 자동 등록된다.
 */
@Configuration
class OpenAPIConfig {

    @Bean
    fun openAPI(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Challenge (MAENGSE) API")
                .version("v1")
                .description(
                    """
                    친구와의 1:1 챌린지 API.

                    응답 규약: 모든 응답은 BaseResponse 패턴. 비즈니스 에러는 HTTP 4xx가 아닌 HTTP 200 + body.code로 구분.
                    code=200 성공 / 700 스낵바 / 701 다이얼로그 / 702~703 전체화면 / 705 단일버튼 / 401 토큰만료 / 500 인프라장애
                    """.trimIndent()
                )
        )
        .addServersItem(Server().url("http://localhost:8080").description("Local"))
        .components(
            Components().addSecuritySchemes(
                "bearerAuth",
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
            )
        )
        .addSecurityItem(SecurityRequirement().addList("bearerAuth"))
}
