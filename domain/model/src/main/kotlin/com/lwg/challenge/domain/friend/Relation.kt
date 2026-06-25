package com.lwg.challenge.domain.friend

/**
 * 검색 결과 사용자별 친구 관계(derived). DB 컬럼 없음 — friendships row 를 보고 service 가 계산해서 응답에 박는다.
 *
 * 정의는 spec-friend-add.md §4.3 참조.
 *
 * - `NONE`              : row 없음
 * - `REQUEST_SENT`      : 내가 보낸 요청이 PENDING 상태
 * - `REQUEST_RECEIVED`  : 상대가 보낸 요청이 PENDING 상태
 * - `FRIEND`            : 어느 방향이든 ACCEPTED
 * - `REJECTED`          : 내가 보낸 요청이 REJECTED 상태 (다시 요청 가능)
 *
 * BLOCKED 는 1차에서 미구현 — V1 schema 의 BLOCKED status 는 검색 노출 경로 없음.
 */
enum class Relation {
    NONE,
    REQUEST_SENT,
    REQUEST_RECEIVED,
    FRIEND,
    REJECTED,
}
