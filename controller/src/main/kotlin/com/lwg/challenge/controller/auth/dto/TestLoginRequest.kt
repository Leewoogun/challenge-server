package com.lwg.challenge.controller.auth.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * `POST /api/v1/auth/test-login` 요청 (dev-test-login).
 *
 * **선택자를 인덱스(`testUserNo`)로 둔 이유** (api-contract 오픈이슈 1 확정):
 * 이건 인증 우회 엔드포인트다. 닉네임 문자열을 받으면 **임의 이름의 계정을 얼마든 만들 수 있는 표면**이
 * 되지만, `1..3` 고정이면 **폭발 반경이 제한**된다. `kakao_id = 999000000 + testUserNo` 규약상
 * 어차피 숫자 인덱스가 필요하기도 하다.
 *
 * 닉네임은 **서버가 소유**한다(`테스터{n}`). 모바일 버튼 라벨도 같은 문자열을 쓴다.
 */
@Schema(description = "개발용 테스트 로그인 요청")
data class TestLoginRequest(
    @field:NotNull(message = "테스트 계정 번호는 1~3이에요")
    @field:Min(value = 1, message = "테스트 계정 번호는 1~3이에요")
    @field:Max(value = 3, message = "테스트 계정 번호는 1~3이에요")
    @field:Schema(example = "1", description = "1~3. 각각 kakao_id 999000001~3 / 닉네임 테스터1~3")
    val testUserNo: Int?,
)
