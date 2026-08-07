-- V8__notification_type_recomment.sql
-- push-fcm feature (T-B3): notifications.type 의 어휘를 재정의한다.
--
-- ============================================================================
-- ⚠️ 이 마이그레이션도 데이터를 바꾸지 않는다. DDL 도 바꾸지 않는다. 주석만 고친다.
--    V6__datetime_kst_convention.sql 과 같은 성격이다.
-- ============================================================================
--
-- ── 왜 스키마 변경이 없나 ───────────────────────────────────────────────────
--
-- `notifications.type` 은 V1 부터 `VARCHAR(30)` 이다. enum 을 DB 타입으로 만들지 않고
-- 문자열로 둔 덕분에, **어휘가 바뀌어도 컬럼은 그대로다.** 가장 긴 이름이
-- `CHALLENGE_ACCEPTED`(18자) / `OPPONENT_VERIFIED`(17자) 라 30 에 한참 못 미친다.
--
-- 다만 V1 의 인라인 주석이 낡았다:
--
--     type VARCHAR(30) NOT NULL,  -- CHALLENGE_REQUEST / SIGN_REQUEST / REMIND / ...
--                                                        ^^^^^^^^^^^^ 이제 존재하지 않는다
--
-- 낡은 주석은 틀린 문서보다 나쁘다 — `\d+ notifications` 로 바로 보이는 자리에 있어서
-- **서버 코드를 안 읽는 사람이 이걸 사실로 믿는다.** 그래서 여기서 못박는다.
--
-- ── SIGN_REQUEST 폐기 사유 ──────────────────────────────────────────────────
--
-- 🔴 **soul-oath 에서 수락과 서명을 원자 요청으로 묶었다.**
-- 상대는 계약서에 서명하는 행위 자체로 수락하며, 별도의 "서명해 주세요" 단계가 없다.
-- 따라서 *"수락은 했지만 아직 서명 전"* 이라는 상태가 **존재하지 않는다.**
-- 알림은 상태 전이를 알리는 것인데 알릴 전이가 사라졌으므로 타입을 폐기한다.
--
-- ⚠️ 되살리려면 먼저 그 중간 상태를 다시 만들어야 한다. 이름만 되돌리면 영원히 발송되지 않는
--    죽은 타입이 된다.
--
-- ── 신규 2종 ────────────────────────────────────────────────────────────────
--
-- CHALLENGE_ACCEPTED / CHALLENGE_REJECTED. 기획서에 없던 공백이었다 —
-- 신청자는 상대가 수락했는지 거절했는지 알 방법이 홈 화면 재방문뿐이었다.
--
-- ── 기존 데이터 ────────────────────────────────────────────────────────────
--
-- notifications 는 발송 배관(T-B4~T-B5)이 아직 없어 row 0 건이다.
-- 즉 `SIGN_REQUEST` 로 저장된 row 가 존재할 수 없어 **정정할 DML 이 없다.**
-- (있었다면 이 자리에서 UPDATE 를 했어야 한다.)

COMMENT ON COLUMN notifications.type IS
    'NotificationType enum name. CHALLENGE_REQUEST / CHALLENGE_ACCEPTED / CHALLENGE_REJECTED / REMIND / OPPONENT_VERIFIED / RESULT / TAUNT / FRIEND_REQUEST. SIGN_REQUEST 는 폐기됨(soul-oath 에서 수락=서명 원자화로 대상 상태 소멸) — push-fcm §0.6';

-- reference_id 는 이름을 중립으로 두고 참조 대상을 주석에 적는다 (push-fcm 오픈 이슈 3 해소).
-- "= challenge_id" 로 규정해 버리면 FRIEND_REQUEST 가 붙는 순간 이름이 거짓이 되거나 컬럼을 새로 파야 한다.
-- 현재 3종이 전부 challenge 라는 건 오늘의 사실이지 규약이 아니다.
-- (soul-oath 의 `_signature_url` → `_signature_data` 개명과 같은 판단이다.)
COMMENT ON COLUMN notifications.reference_id IS
    '알림 타입에 따라 참조 대상이 다르다. 현재 CHALLENGE_REQUEST/CHALLENGE_ACCEPTED/CHALLENGE_REJECTED 3종은 전부 challenges.id. FK 제약을 걸지 않는 이유가 이것이다 — 타입 추가 시 이 주석을 갱신할 것 — push-fcm §0.6';
