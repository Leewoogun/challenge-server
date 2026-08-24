package com.lwg.challenge.domain.notification

/**
 * 푸시 알림 문구 (계약 §3 문구 표).
 *
 * ## 문구 확정 상태 — 이 파일이 유일한 문구 출처다
 *
 * | 타입 | 상태 |
 * |---|---|
 * | `CHALLENGE_REQUEST` | ✅ **사용자 확정 (2026-08-07)** — 격식체 |
 * | `CHALLENGE_ACCEPTED` / `CHALLENGE_REJECTED` | 🟡 **초안값** — 디자인 확인 후 바뀔 수 있다 |
 * | `OPPONENT_VERIFIED` | 🟡 **초안값 (2026-08-14 추가)** — challenge-verification T-B4 |
 *
 * ⚠️ **지금 네 종의 톤이 갈려 있다. 단 갈린 축은 `title` 이지 존댓말/반말이 아니다.**
 * `body` 는 **넷 다 합니다체**다 (`~하시렵니까?` / `~수락했습니다` / `~도망쳤습니다` / `~올렸습니다`).
 * 갈리는 것은 제목이다 — `영혼의 맹세`(제품 개념명) / `계약 완료.`(평서 단정) / `ㅠㅠ`(구어) /
 * `증거 도착`(사실 통보).
 * `CHALLENGE_REQUEST` 는 제품 개념명을 제목으로 쓰는 계약 제안이라 격식체가 의도된 것이고,
 * 나머지 셋은 아직 초안이다.
 *
 * 🔴 **이 표가 한때 나머지 둘을 "반말" 이라고 적었다. 사실이 아니었다** — `e76c64c` 가 문자열을
 * 존댓말로 교체하면서 상태 표기를 함께 고치지 못한 흔적이다. **톤을 확정할 사람이 이 표를 보고
 * 판단하므로, 표가 현재 톤을 잘못 말하면 판단 자체가 틀어진다.**
 * **나머지 셋도 확정할 때 톤을 맞출지 갈라둘지 함께 정해야 한다** — 지금 상태를 "일관성 결함"으로
 * 보고 임의로 통일하지 마라. 확정분을 초안에 맞추는 방향은 특히 안 된다.
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
        // ✅ **사용자 확정 (2026-08-07).** 아래 두 종과 톤이 다른 것은 의도다 —
        //    제목이 제품 개념명("영혼의 맹세")이고 본문이 계약 제안이라 격식체를 쓴다.
        //    `soul-oath` 계약서 본문이 격식체인 것과 같은 결이다
        //    ("화면은 앱이 나에게 말을 거는 것이고, 계약서는 문서다").
        NotificationType.CHALLENGE_REQUEST -> Message(
            title = "영혼의 맹세",
            body = "${actorNickname}과 계약을 하시렵니까?",
        )
        NotificationType.CHALLENGE_ACCEPTED -> Message(
            title = "계약 완료.",
            body = "${actorNickname}님이 수락했습니다",
        )

        NotificationType.CHALLENGE_REJECTED -> Message(
            title = "ㅠㅠ",
            body = "${actorNickname}님이 도망쳤습니다",
        )

        // 🟡 **초안값 (challenge-verification T-B4, 2026-08-14).** 사용자 확정 대상이다.
        //
        // 🔴 위 세 종에 톤을 맞추지 않았다. KDoc 이 "임의로 통일하지 마라, 확정분을 초안에 맞추는
        //    방향은 특히 안 된다" 고 명시하므로, 제목은 이 알림이 알리는 사실 그대로 뒀다.
        //    (`body` 만 나머지와 같은 합니다체다 — 그건 원래 넷이 공통이던 축이다.)
        //
        // ⚠️ **이 한 줄이 발송 스위치다.** push-fcm 계약 §0.6.1 이 "문구 추가 = 발송 개시" 로
        //    규정했고, 지우면 그 타입의 발송이 즉시 멈춘다(dispatcher 가 null 을 보고 건너뛴다).
        //    2026-08-14 에 절차를 밟고 열었다 — verif-mobile 이 `PushEvent.OpponentVerified` 와
        //    딥링크(`Route.Challenge.Detail`)를 반영하고 회신한 뒤에 넣었다.
        //    **다음에 새 타입을 열 때도 같은 순서를 지켜라. 여기 먼저 쓰면 구버전 앱에서
        //    "눌러도 아무 데도 안 가는" 조용한 실패가 난다.**
        NotificationType.OPPONENT_VERIFIED -> Message(
            title = "증거 도착",
            body = "${actorNickname}님이 인증 사진을 올렸습니다",
        )

        // 🔴 아래 4종은 선행 feature 가 없어 트리거 자체가 존재하지 않는다.
        // 각 feature 가 붙을 때 문구를 여기에 추가하면 배관은 그대로 재사용된다.
        NotificationType.REMIND,
        NotificationType.RESULT,
        NotificationType.TAUNT,
        NotificationType.FRIEND_REQUEST,
        -> null
    }

    /** 닉네임 조회에 실패했을 때 쓰는 대체 문자열. `ChallengeCommandService` 와 같은 값이다. */
    const val UNKNOWN_NICKNAME = "(알 수 없음)"
}
