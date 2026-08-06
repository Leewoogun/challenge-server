-- V7__soul_oath_signature_data.sql
-- soul-oath feature: 서명 컬럼을 URL 이 아니라 **벡터 스트로크 데이터** 저장용으로 정리한다.
--
-- ============================================================================
-- 왜 rename 하는가 — 컬럼 이름이 거짓이 되기 때문
-- ============================================================================
--
-- V1 은 `challenger_signature_url` / `opponent_signature_url` 로 만들어졌다. 이름대로 가려면
-- 서명 이미지를 어딘가에 올려야 하고, 그러면 **파일 스토리지 ADR 이 선행**돼야 한다
-- (프로젝트에 아직 없다. ADR-0007 local→AWS 와 연결됨).
--
-- soul-oath 는 그 대신 **서명을 벡터 스트로크로 DB 에 저장**하기로 확정했다:
--   - 서명은 손가락 획의 좌표 목록이다. 수 KB 면 충분하고 렌더는 Compose `Path` 로 그대로 그린다.
--   - 파일 스토리지 ADR·인프라·수명주기·CDN 을 전부 회피한다.
--   - 벡터라 어느 해상도에서도 깨지지 않는다.
--
-- 저장하는 값이 URL 이 아니므로 **컬럼 이름을 그대로 두면 다음 사람이 반드시 오해한다.**
--
-- ── 왜 `_strokes` 가 아니라 `_data` 인가 ────────────────────────────────────
--
-- `_url` 이 문제였던 진짜 이유는 **"저장 표현을 컬럼 이름에 박았다"** 는 것이다.
-- `_strokes` 는 같은 실수를 다른 표현으로 반복한다 — 포맷이 바뀌면 또 거짓이 된다.
-- **이름은 중립적으로 두고, 정확한 형식은 아래 COMMENT 에 남긴다**(DB 에서 검색 가능한 자리).
--
-- ── 데이터 이관 ────────────────────────────────────────────────────────────
--
-- `contracts` row = 0 (2026-08-03 실측). **지금이 rename 이 가장 싼 시점이다.**
-- 실사용 계약서가 쌓인 뒤엔 컬럼 의미 변경에 데이터 해석 문제가 따라붙는다.
--
-- ── 파일 스토리지는 언제 필요한가 ──────────────────────────────────────────
--
-- **카메라 인증**(`verifications.photo_url`)이다. 사진은 벡터로 대체 불가다.
-- 그때 ADR 을 쓰는 게 맞고, 서명을 이유로 앞당기지 않는다.

-- ========== 1. 컬럼 rename ==========

ALTER TABLE contracts RENAME COLUMN challenger_signature_url TO challenger_signature_data;
ALTER TABLE contracts RENAME COLUMN opponent_signature_url   TO opponent_signature_data;

-- ========== 2. 저장 형식을 컬럼 주석에 명시 (ADR-0010 의 COMMENT ON COLUMN 17건 선례 승계) ==========
--
-- 이름을 중립적으로 둔 대가로, 형식은 반드시 찾을 수 있어야 한다.
-- `\d+ contracts` 한 번이면 서버 코드를 안 읽는 사람(운영·분석·다음 담당자)도 형식을 안다.

COMMENT ON COLUMN contracts.challenger_signature_data IS
    '서명 벡터 스트로크 JSON. 예: {"v":1,"g":1000,"s":[[123,456,130,460],[700,200,705,210]]} — '
    'v=포맷 버전(현재 1), g=그리드 해상도(현재 1000), s=획 배열이며 각 획은 x,y 가 번갈아 나오는 평탄 Int 배열(길이는 항상 짝수). '
    '좌표는 0..g 정수로 양자화된 정규화 좌표라 어느 해상도에서도 렌더된다. NULL = 미서명 — soul-oath';

COMMENT ON COLUMN contracts.opponent_signature_data IS
    '서명 벡터 스트로크 JSON. 형식은 challenger_signature_data 와 동일. NULL = 미서명 — soul-oath';

COMMENT ON COLUMN contracts.is_finalized IS
    '양측 서명 완료 여부. true 가 된 시점에만 challenges.status 가 IN_PROGRESS 로 전환된다. '
    '⚠️ ChallengeStatus.CONTRACT_SIGNING 은 도입하지 않았다 — 그 상태의 챌린지는 '
    '/challenges/received(PENDING만)에도 /challenges/active(IN_PROGRESS만)에도 안 잡혀 재개 경로가 사라진다. '
    '계약 완결 여부는 contract 의 속성이지 challenge 의 상태가 아니다 — soul-oath';

COMMENT ON COLUMN contracts.content IS
    '계약서 본문. 서버가 정해진 템플릿으로 채운다(사용자 커스터마이즈 비범위) — soul-oath';

-- ========== 3. 시간 컬럼 KST 주석 (ADR-0010 일관성) ==========
--
-- V6 에서 다른 테이블의 시간 컬럼에 KST 기준을 박았다. contracts 의 서명 시각도 같은 규약이다.
-- (V6 는 contracts.created_at / *_signed_at 을 이미 다뤘으나, rename 과 함께 서명 시각의
--  의미를 명확히 해 둔다 — "언제 맹세했는가" 는 이 feature 의 핵심 기록이다.)

COMMENT ON COLUMN contracts.challenger_signed_at IS
    'KST(UTC+9) 기준. 챌린저가 맹세한 시각. NULL = 미서명 — ADR-0010 / soul-oath';
COMMENT ON COLUMN contracts.opponent_signed_at IS
    'KST(UTC+9) 기준. 상대가 맹세한 시각. NULL = 미서명 — ADR-0010 / soul-oath';

-- ========== 참고: FK 는 그대로 둔다 (ON DELETE CASCADE 를 붙이지 않는다) ==========
--
-- `contracts_challenge_id_fkey` 에는 ON DELETE 절이 없다. **의도적이다.**
--
-- 챌린지 취소는 물리 삭제인데(challenge-create 스코프 결정), contract 가 있으면 FK 위반이 난다.
-- 이를 CASCADE 로 푸는 대신 **서비스가 contract 를 명시적으로 먼저 삭제**한다.
--
-- 근거: 이 제품의 컨셉은 "무를 수 없는 약속"이다. CASCADE 는 **다른 이유로 challenge 를 지울 때도
-- 맹세를 조용히 함께 지운다.** 물리 삭제 + 감사 추적 없음이라는 기존 선택 위에 암묵적 연쇄 삭제까지
-- 얹으면 되돌릴 근거가 사라진다. 명시적 삭제는 코드에 순서가 보인다.
--
-- 거절(REJECTED)은 challenge row 를 보존하므로 FK 문제가 없고, contract 도 **보존**한다
-- ("거절당한 맹세"도 이력이다).
