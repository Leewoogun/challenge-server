package com.lwg.challenge.controller.common

/**
 * 전역 시간 표기 규약 (ADR-0010 / datetime-model-migration api-contract §1.1).
 *
 * ```
 * 날짜+시간 : "2026-07-28 15:00:00"   ← 구분자는 공백. T 없음
 * 날짜 전용 : "2026-07-28"
 * ```
 *
 * **offset · `Z` suffix · 밀리초 · 나노초를 붙이지 않는다. 모든 값은 KST 기준이다.**
 *
 * ⚠️ **ISO-8601 이 아니다** — ISO 는 날짜와 시간 사이에 `T` 를 요구한다.
 * 모바일은 이 패턴 전용 커스텀 파서를 쓰고 `T` 구분자가 오면 **실패한다**(실패 자체가 테스트로 고정돼 있다).
 * 관대하게 둘 다 받아주지 않으므로 서버가 표기를 흘리면 조용히 통과하지 않는다.
 *
 * 상수로 빼 둔 이유: `@JsonFormat(pattern = ...)` 은 컴파일 타임 상수만 받는데, 문자열을 DTO 마다
 * 손으로 적으면 오타가 나도 컴파일이 통과한다. 한 곳에서 참조하면 그 실패 모드가 사라진다.
 */
const val WIRE_DATETIME = "yyyy-MM-dd HH:mm:ss"

/** 날짜 전용 필드 패턴. 현재 `challengeDate` 하나만 쓴다 (api-contract §2.4). */
const val WIRE_DATE = "yyyy-MM-dd"
