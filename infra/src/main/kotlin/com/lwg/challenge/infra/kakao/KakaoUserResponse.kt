package com.lwg.challenge.infra.kakao

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Kakao `/v2/user/me` 응답 중 필요한 필드만.
 *
 * Kakao 원문 shape:
 * ```
 * {
 *   "id": 123456789,
 *   "kakao_account": {
 *     "phone_number_needs_agreement": false,
 *     "phone_number": "+82 10-1234-5678",
 *     "profile_needs_agreement": false,
 *     "profile": {
 *       "nickname": "홍길동",
 *       "profile_image_url": "https://..."
 *     }
 *   }
 * }
 * ```
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KakaoUserResponse(
    @JsonProperty("id")
    val id: Long,

    @JsonProperty("kakao_account")
    val kakaoAccount: KakaoAccount?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KakaoAccount(
    @JsonProperty("phone_number_needs_agreement")
    val phoneNumberNeedsAgreement: Boolean? = null,

    @JsonProperty("phone_number")
    val phoneNumber: String? = null,

    @JsonProperty("profile_needs_agreement")
    val profileNeedsAgreement: Boolean? = null,

    @JsonProperty("profile")
    val profile: KakaoProfile? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KakaoProfile(
    @JsonProperty("nickname")
    val nickname: String? = null,

    @JsonProperty("profile_image_url")
    val profileImageUrl: String? = null,
)
