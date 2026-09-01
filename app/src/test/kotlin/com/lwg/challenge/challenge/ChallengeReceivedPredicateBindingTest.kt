package com.lwg.challenge.challenge

import com.lwg.challenge.infra.jpa.challenge.ChallengeJpaRepository
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.Query

/**
 * `ChallengeJpaRepository` 의 **받은 도전장 술어 두 벌이 갈라지지 않게** 묶어 둔다.
 *
 * ## 🔴 왜 필요한가 — 같은 도메인 규칙이 두 쿼리에 복제돼 있다
 *
 * *"이 사용자가 아직 응답을 기다리는 신청"* 이라는 **하나의 규칙**을 두 쿼리가 각자 구현한다:
 *
 * | 메서드 | 종류 | 쓰이는 곳 |
 * |---|---|---|
 * | `findReceivedPending` | **native** | 홈 '받은 도전장' 목록 |
 * | `findPendingReceivedIdsIn` | **JPQL** | 알림 `referencePending` |
 *
 * 둘이 어긋나면 **알림은 탭되는데 홈엔 그 카드가 없다** (또는 그 반대). 🔴 두 쿼리 다 성공하고
 * 응답도 200 이라 **예외도 로그도 남지 않는다** — 사용자에게만 보이고 서버는 정상으로 보고한다.
 *
 * 특히 **`deadline > :now` 가 한쪽에서 빠지는 것**이 가장 무거운 실패다. 마감이 지난 row 는
 * lazy expiry 때문에 DB 에서 `PENDING` 인 채로 정상적으로 남아 있으므로
 * (`ChallengeCommandService` KDoc — *"마감 지난 row 는 DB 에서 PENDING 인 채로 남고 응답에서만
 * 빠진다"*), 조건이 빠지면 **만료된 신청이 그대로 "대기 중"으로 나간다.** `referencePending` 이
 * 애초에 고치려던 버그의 재발이다.
 *
 * ## 왜 합치지 않고 테스트로 묶었는가
 *
 * native 와 JPQL 이라 컬럼 표기부터 다르고(`c.opponent_id` vs `c.opponentId`), SELECT 절·JOIN·
 * 정렬도 다르다. 합치려면 둘 중 하나를 상대 쪽 형태로 끌고 와야 하는데 그 편이 더 위험하다.
 * 그래서 **구현은 둘로 두고, 세 조건이 양쪽에 다 있다는 사실만 여기서 고정한다.**
 *
 * 선례: `NotificationDispatcherWiringTest` 가 어노테이션 존재를 구조적으로 단언한다 —
 * *"빠뜨려도 아무것도 실패하지 않는" 것을 실패하게 만든다*는 점에서 같은 계열이다.
 *
 * ## ⚠️ 이 테스트가 증명하지 **못하는** 것
 *
 * **쿼리 문자열만 본다. *"두 술어의 의미가 같다"* 는 증명하지 못한다.** 조건이 양쪽에 다 있어도
 * 파라미터가 다른 값으로 바인딩되거나, 조인/서브쿼리가 결과 집합을 다르게 자르면 이 테스트는
 * 통과하면서 두 화면은 어긋날 수 있다.
 *
 * 막으려는 실패 모드는 **«한쪽에서 조건을 빼는 것» 하나**이고, 그건 정확히 겨눈다. 그 이상으로
 * 읽히면 안 된다. **의미 검증은 `NotificationReferencePendingIntegrationTest`** 가 실제 DB 로
 * 한다 — 다만 그쪽은 Docker 가 있을 때만 돌고, **이 테스트는 항상 돈다.** 둘의 역할 분담이 그것이다.
 */
class ChallengeReceivedPredicateBindingTest {

    /** 홈 '받은 도전장' — native. */
    private val receivedPendingSql: String = queryOf("findReceivedPending")

    /** 알림 `referencePending` — JPQL. */
    private val pendingIdsSql: String = queryOf("findPendingReceivedIdsIn")

    // ─── 1. status = 'PENDING' ───────────────────────────────────

    @Test
    fun `두 쿼리 모두 status 를 PENDING 으로 건다 - 빠지면 이미 끝난 신청까지 대기 중으로 나간다`() {
        val pattern = Regex("""status\s*=\s*'PENDING'""")

        assertBothMatch(
            pattern = pattern,
            subject = "status = 'PENDING'",
            why = "한쪽에서 빠지면 수락·거절·취소가 끝난 챌린지까지 «응답 대기» 로 집힌다",
        )
    }

    // ─── 2. deadline > :now (🔴 >= 가 아니다) ────────────────────

    @Test
    fun `두 쿼리 모두 deadline 에 초과 비교를 건다 - 빠지면 만료된 신청이 대기 중으로 나간다`() {
        // 🔴 `(?!=)` 가 핵심이다 — `deadline >= :now` 로 바뀌면 여기서 걸려야 한다.
        // 마감 정각(`deadline == now`)은 아직 유효하다는 `KstDeadlineCalculator.isExpired` 의
        // 경계 규칙과 두 쿼리가 함께 어긋나지 않게 고정한다.
        val pattern = Regex("""deadline\s*>(?!=)\s*:""")

        assertBothMatch(
            pattern = pattern,
            subject = "deadline > :param (>= 가 아니라 >)",
            why = "lazy expiry 로 마감 지난 row 가 DB 에 PENDING 인 채 남는다. " +
                "이 조건이 빠지면 만료된 신청이 «대기 중» 으로 나가고, 그게 referencePending 이 고치려던 버그다",
        )
    }

    // ─── 3. opponent 조건 ────────────────────────────────────────

    @Test
    fun `두 쿼리 모두 opponent 로 방향을 건다 - 빠지면 내가 보낸 신청까지 받은 것으로 집힌다`() {
        // native 는 `opponent_id`, JPQL 은 `opponentId` — 표기만 다르고 같은 컬럼이다.
        // [fold] 가 `_` 제거 + 소문자화로 그 차이를 지운 뒤 하나의 패턴으로 본다.
        val pattern = Regex("""opponentid\s*=\s*:""")

        assertBothMatch(
            pattern = pattern,
            subject = "opponent 조건 (native: opponent_id / JPQL: opponentId)",
            why = "빠지면 방향이 사라져 내가 «보낸» 신청까지 «받은» 것으로 집힌다",
            transform = ::fold,
        )
    }

    // ─── helpers ─────────────────────────────────────────────────

    /**
     * [name] 메서드에 붙은 `@Query` 의 문자열.
     *
     * 파라미터 타입으로 찾지 않고 이름으로 찾는다 (`NotificationDispatcherWiringTest` 와 같은 형태)
     * — 시그니처가 바뀌었다고 이 테스트가 **틀린 이유로** 깨지면 안 된다. 여기서 고정하려는 것은
     * WHERE 절이지 파라미터 목록이 아니다.
     */
    private fun queryOf(name: String): String {
        val method = ChallengeJpaRepository::class.java.declaredMethods.single { it.name == name }
        val query = requireNotNull(method.getAnnotation(Query::class.java)) {
            "$name 에 @Query 가 없다. 파생 쿼리(메서드 이름 파싱)로 바뀌었다면 이 테스트가 " +
                "술어를 볼 방법이 없어진다 — 두 술어를 묶는 다른 장치를 먼저 만들어라."
        }
        return squash(query.value)
    }

    /** 공백·개행 차이로 깨지지 않게 정규화. **대소문자는 보존한다** — `'PENDING'` 리터럴을 봐야 한다. */
    private fun squash(sql: String): String = sql.replace(Regex("""\s+"""), " ").trim()

    /**
     * [squash] + 소문자화 + `_` 제거. **컬럼 표기가 native/JPQL 로 갈리는 조건에만** 쓴다.
     *
     * ⚠️ status 리터럴 검사에는 쓰지 마라 — 소문자로 접으면 `status = 'pending'` 도 통과하는데,
     * 그건 DB 에 대문자 enum name 이 저장되므로 **조용히 빈 결과가 되는 실제 버그**다.
     */
    private fun fold(sql: String): String = squash(sql).lowercase().replace("_", "")

    private fun assertBothMatch(
        pattern: Regex,
        subject: String,
        why: String,
        transform: (String) -> String = { it },
    ) {
        val received = transform(receivedPendingSql)
        val pendingIds = transform(pendingIdsSql)
        val inReceived = pattern.containsMatchIn(received)
        val inPendingIds = pattern.containsMatchIn(pendingIds)

        assertTrue(
            inReceived && inPendingIds,
            """
            🔴 받은 도전장 술어 두 벌이 갈라졌다 — «$subject» 가 한쪽에만 있다.
               findReceivedPending      : ${verdict(inReceived)}
               findPendingReceivedIdsIn : ${verdict(inPendingIds)}

            두 쿼리는 «이 사용자가 아직 응답을 기다리는 신청» 이라는 같은 도메인 규칙의 두 구현이다.
            갈라지면 홈 '받은 도전장'과 알림 referencePending 이 서로 다른 말을 한다 —
            알림은 탭되는데 홈엔 그 카드가 없거나, 반대다. 예외도 로그도 남지 않는다.

            이 조건이 필요한 이유: $why

            한쪽을 의도적으로 바꾼 것이라면 **다른 쪽도 같이 바꿔라.** 정말로 갈라져야 한다면
            두 메서드의 KDoc(«같아야 한다» 경고)부터 고치고 이 테스트를 다시 설계해라.

            [찾은 패턴] ${pattern.pattern}

            [findReceivedPending]
            $received

            [findPendingReceivedIdsIn]
            $pendingIds
            """.trimIndent(),
        )
    }

    private fun verdict(found: Boolean): String = if (found) "있음" else "🔴 없음"
}
