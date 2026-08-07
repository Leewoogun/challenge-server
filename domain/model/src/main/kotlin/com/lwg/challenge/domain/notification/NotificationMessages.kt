package com.lwg.challenge.domain.notification

/**
 * 푸시 알림 문구 (계약 §3 문구 표).
 *
 * ## 🟡 문구 미확정 — 이 파일이 유일한 문구 출처다
 *
 * ⚠️ **아래 3종은 계약 초안값이며 확정이 아니다.** Lovable 화면별 문구 톤이 이미 갈려 있고
 * (challenge-create/design.md §9), 반말/존댓말 축이 미결이다. 디자인 확인 후 바뀐다.
 *
 * **그래서 문자열을 코드 곳곳에 흩지 않고 여기 모았다.** 문구가 확정되면 이 파일만 고치면 되고,
 * 나중에 흩어진 문자열을 찾아다닐 일이 없다. 🔴 **새 알림 타입을 추가할 때도 문구는 반드시 여기에
 * 둬라** — 리스너나 서비스에 직접 문자열을 쓰면 그 순간 이 규약이 깨진다.
 *
 * ## 저장 값과 발송 값이 같다
 *
 * `notifications.title`/`body` 에 **여기서 만든 문구를 그대로 박제**한다. 계약 오픈 이슈 2
 * ("박제 vs 목록 화면에서 재조립")에서 박제를 택한 셈인데, 근거는 이렇다:
 * - 목록 화면이 아직 없어 재조립 쪽 요구사항이 존재하지 않는다.
 * - 푸시로 이미 나간 문구와 목록에 보이는 문구가 **다르면 그게 버그로 보인다.**
 *
 * ⚠️ 대가: 문구를 바꿔도 **과거 알림은 옛 문구 그대로 남는다.** 그게 오히려 "그때 이렇게 왔었다"에
 * 부합한다고 봤다. 목록 화면 feature 가 이 판단을 뒤집고 싶으면 그때 재검토하면 된다.
 */
object NotificationMessages {

    /** 알림 하나의 제목 + 본문. */
    data class Message(val title: String, val body: String)

    /**
     * [type] 과 상대 닉네임으로 문구를 만든다.
     *
     * @param actorNickname 알림을 유발한 상대의 닉네임. 조회 실패 시 [UNKNOWN_NICKNAME] 로 들어온다.
     * @return 이번 범위 밖 타입이면 null — **발송하지 않는다.** 빈 문자열이나 임시 문구를 만들어
     *         내보내면 사용자에게 깨진 알림이 가므로, 문구가 없으면 아예 보내지 않는 쪽이 맞다.
     */
    fun of(type: NotificationType, actorNickname: String): Message? = when (type) {
        NotificationType.CHALLENGE_REQUEST -> Message(
            title = "새 도전장이 왔어!",
            body = "${actorNickname}님이 도전장을 보냈어",
        )

        NotificationType.CHALLENGE_ACCEPTED -> Message(
            title = "도전이 성사됐어!",
            body = "${actorNickname}님이 도전을 받아쳤어",
        )

        NotificationType.CHALLENGE_REJECTED -> Message(
            title = "도전이 거절됐어",
            body = "${actorNickname}님이 도전을 피했어...",
        )

        // 🔴 아래 5종은 선행 feature 가 없어 트리거 자체가 존재하지 않는다.
        // 각 feature 가 붙을 때 문구를 여기에 추가하면 배관은 그대로 재사용된다.
        NotificationType.REMIND,
        NotificationType.OPPONENT_VERIFIED,
        NotificationType.RESULT,
        NotificationType.TAUNT,
        NotificationType.FRIEND_REQUEST,
        -> null
    }

    /** 닉네임 조회에 실패했을 때 쓰는 대체 문자열. `ChallengeCommandService` 와 같은 값이다. */
    const val UNKNOWN_NICKNAME = "(알 수 없음)"
}
