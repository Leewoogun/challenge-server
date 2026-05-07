package com.lwg.challenge.infra.external.kakao

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

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
