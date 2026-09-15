-- V12__challenge_forfeit.sql
-- challenge-forfeit feature (T-B2 / T-B4): 기권 컬럼 1개 + 알림 어휘 재정의.
--
-- ============================================================================
-- DDL 은 `ALTER TABLE challenges ADD COLUMN forfeited_by` 한 줄뿐이다.
-- 나머지는 "왜 그 한 줄인가" 와, 낡아버린 주석의 정정이다 (V6/V8/V9/V11 선례).
-- ============================================================================
--
-- ── 기권이란 무엇인가 (사용자 결정 2026-09-05) ──────────────────────────────
--
-- 🔴 기권은 *"나는 인증 안 한 것으로 친다"* 가 **아니다.** **"상대에게 승리를 준다"** 는
--    선언이다. 그래서 기권자와 상대가 **둘 다 미인증**이어도 상대가 이긴다 —
--    기존 규칙만 있었다면 그 자리는 BOTH_LOSE 였다.
--    판정 순서는 `ChallengeVerdict.decide` 가 유일한 출처다(기권 → 인증 4규칙).
--
-- ── 🔴 왜 `forfeited_at` 을 만들지 않았는가 ─────────────────────────────────
--
-- 기권은 **즉시 COMPLETED** 다. 자정 배치를 기다리지 않고 그 자리에서 판정·전적 집계까지
-- 끝난다. 따라서 **`completed_at` 이 곧 기권 시각**이다.
--
-- 값이 같은 컬럼을 둘 두면 언젠가 갈린다 — 한쪽만 갱신하는 경로가 생기거나, 소급 정정이
-- 한쪽만 닿거나. 그때 *"둘 중 뭐가 진짜냐"* 를 판단할 근거가 코드 어디에도 없다.
-- 🔴 **기권 시각이 필요하면 `completed_at` 을 읽어라. 컬럼을 늘리지 마라.**
--
-- ── 🔴 왜 BOOLEAN 이 아니라 user id 인가 ────────────────────────────────────
--
-- `forfeited BOOLEAN` 으로 두면 *"누가 기권했나"* 를 알기 위해
-- `forfeited AND result = 'OPPONENT_WIN' → challenger 가 기권` 으로 **역산**해야 한다.
-- 그건 판정 규칙의 사본을 읽는 쪽마다 심는 것이다. id 면 challenger_id / opponent_id 와
-- **비교만** 하면 되고, 비교는 규칙이 아니다.
--
-- ── 🔴 왜 FK 를 걸지 않는가 ────────────────────────────────────────────────
--
-- 이 값은 **언제나 challenger_id 또는 opponent_id 중 하나**이고, 그 둘은 이미
-- `REFERENCES users(id)` 다. 세 번째 FK 는 이미 보증된 사실을 한 번 더 강제할 뿐이다.
-- (탈퇴는 users row 를 지우지 않고 익명화하므로 — V11 — 참조가 끊길 경로도 없다.)
--
-- ── 인덱스를 만들지 않는 이유 ──────────────────────────────────────────────
--
-- 이 컬럼으로 **조회하는 쿼리가 없다.** 기권 건은 challenge id 로 찾아 읽고, 목록은 전부
-- status / deadline / challenge_date 축이다. 쓰이지 않는 인덱스는 INSERT·UPDATE 비용만 낸다.
--
-- ── 기존 데이터 ────────────────────────────────────────────────────────────
--
-- NULL 허용 컬럼 추가라 기존 row 는 전부 NULL = "기권 없이 끝났거나 아직 안 끝났다" 로
-- 정확히 해석된다. **정정할 DML 이 없다.**

ALTER TABLE challenges ADD COLUMN forfeited_by BIGINT;

COMMENT ON COLUMN challenges.forfeited_by IS
    '기권한 사람의 users.id. NULL = 기권 없이 끝났거나 아직 안 끝났다. 값이 있으면 status 는 반드시 COMPLETED 이고 result 는 그 반대편의 승리다. 기권 시각은 별도 컬럼이 아니라 completed_at 이다(기권은 즉시 COMPLETED) — challenge-forfeit T-B2';

-- ============================================================================
-- 알림 어휘 재정의 — V8 주석이 낡았다 (T-B4)
-- ============================================================================
--
-- V8 이 `COMMENT ON COLUMN notifications.type` 에 어휘 8종을 못박으면서
-- *"타입 추가 시 이 주석을 갱신할 것"* 이라고 적어 뒀다. 지금이 그때다.
--
-- ── 신규 1종 ────────────────────────────────────────────────────────────────
--
-- CHALLENGE_FORFEITED — 수신자는 **기권당한 쪽(= 이긴 쪽)**. reference_id = challenges.id.
-- 이름 길이 19자로 VARCHAR(30) 안이다 (기존 최장 CHALLENGE_ACCEPTED 18자).
--
-- ── reference_id 주석도 함께 고친다 ────────────────────────────────────────
--
-- V8 주석이 *"현재 CHALLENGE_REQUEST/CHALLENGE_ACCEPTED/CHALLENGE_REJECTED 3종"* 이라고
-- 적었는데, 그 뒤 challenge-verification 이 OPPONENT_VERIFIED 발송을 열면서 이미 4종이
-- 됐고 주석은 갱신되지 않았다. 여기서 그 누락까지 함께 정정한다 —
-- **낡은 주석은 `\d+ notifications` 로 바로 보이는 자리에 있어서 서버 코드를 안 읽는
--   사람이 이걸 사실로 믿는다** (V8 이 스스로 적은 이유).

COMMENT ON COLUMN notifications.type IS
    'NotificationType enum name. CHALLENGE_REQUEST / CHALLENGE_ACCEPTED / CHALLENGE_REJECTED / CHALLENGE_FORFEITED / REMIND / OPPONENT_VERIFIED / RESULT / TAUNT / FRIEND_REQUEST. SIGN_REQUEST 는 폐기됨(soul-oath 에서 수락=서명 원자화로 대상 상태 소멸) — push-fcm §0.6 / challenge-forfeit T-B4';

COMMENT ON COLUMN notifications.reference_id IS
    '알림 타입에 따라 참조 대상이 다르다. 현재 발송되는 CHALLENGE_REQUEST/CHALLENGE_ACCEPTED/CHALLENGE_REJECTED/OPPONENT_VERIFIED/CHALLENGE_FORFEITED 5종은 전부 challenges.id. FK 제약을 걸지 않는 이유가 이것이다 — 타입 추가 시 이 주석을 갱신할 것 — push-fcm §0.6 / challenge-forfeit T-B4';
