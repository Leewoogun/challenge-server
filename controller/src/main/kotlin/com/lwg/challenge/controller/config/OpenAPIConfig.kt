package com.lwg.challenge.controller.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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

                    응답 규약: 모든 응답은 BaseResponse 패턴. 비즈니스 에러는 HTTP 4xx 가 아닌 HTTP 200 + body.code 로 구분.
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
