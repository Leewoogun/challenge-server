package com.lwg.challenge.domain.challenge

/**
 * 요청자 기준으로 본 당사자 — **나** 또는 **상대**.
 *
 * [Forfeit] 이 **역할 기준**(challenger/opponent)인 데 반해 이쪽은 **"나" 기준**이다.
 * [Outcome] 과 같은 축이며, 상세 화면의 `forfeitedBy` 가 이 값으로 나간다.
 * 앱은 "누가 기권했나" 를 내 userId 와 비교하지 않고 바로 그린다.
 */
enum class RelativeParty { ME, OPPONENT }
