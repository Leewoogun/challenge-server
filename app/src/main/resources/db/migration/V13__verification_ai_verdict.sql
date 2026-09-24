-- AI 인증 판별 — 판정 로그 4컬럼 (ai-verification T-B2, ADR-0013)
--
-- 🔴 이 컬럼들의 존재 이유는 비용 추적이 아니라 rubric 튜닝의 **유일한 근거**이기 때문이다.
--    오탐 신고가 들어왔을 때 "그때 AI 가 뭘 보고 왜 튕겼는지" 를 못 보면 임계값을 감으로만
--    만지게 된다. 판정은 자정에 확정되고 되돌릴 경로가 없으므로(ADR-0013 §근거 5) 사후에
--    남는 것이 이 4줄뿐이다.
--
-- 🔴 전부 nullable 이고 그게 정상 상태다. 과거 row 는 물론이고, 앞으로도
--    미제출 건 · 기권 건 · fail-open 으로 호출이 안 된 건은 영구히 NULL 로 남는다.

ALTER TABLE verifications
    ADD COLUMN ai_verdict    VARCHAR(20)      NULL,
    ADD COLUMN ai_confidence DOUBLE PRECISION NULL,
    ADD COLUMN ai_reason     TEXT             NULL,
    ADD COLUMN ai_checked_at TIMESTAMP        NULL;

COMMENT ON COLUMN verifications.ai_verdict IS
    'PASS / REJECT / UNCERTAIN / UNVERIFIABLE / ERROR. NULL = 아직 검증하지 않음(미제출·기권·fail-open 포함)';

-- 🔴 NUMERIC(3,2) 가 아니라 DOUBLE PRECISION 인 데는 이유가 둘 있다.
--
--    1. `ddl-auto=validate` 가 Kotlin `Double?` 에 float(53) 을 요구한다. NUMERIC 으로 두면
--       **전 테스트가 통과하는데 서버가 기동하지 않는다** (2026-09-22 실측으로 겪은 함정).
--       NUMERIC 을 고수하려면 엔티티 필드를 BigDecimal 로 바꿔야 한다.
--    2. 🔑 더 중요한 쪽 — NUMERIC(3,2) 는 **0.799 를 0.80 으로 반올림**한다. 판정 자체는
--       메모리의 Double 로 이미 끝나 있어 승패가 뒤집히지는 않지만, **이 컬럼에 남는 기록이
--       실제 값과 달라진다.** 임계(0.80) 바로 아래 값이 임계값으로 보이면 이 컬럼의 존재
--       이유(rubric 튜닝의 유일한 근거)가 그 지점에서 무효가 된다 — 하필 가장 튜닝하고 싶은
--       구간이 경계 근처다.
COMMENT ON COLUMN verifications.ai_confidence IS
    '0.00~1.00. REJECT 이면서 0.80 이상일 때만 미인증 처리한다 (ADR-0013 §결정 2). 그 외는 전부 통과';

COMMENT ON COLUMN verifications.ai_reason IS
    'AI 가 남긴 한 줄 사유. 🔴 사용자 화면에 그대로 노출된다 — 톤이 곧 제품 문구다';

COMMENT ON COLUMN verifications.ai_checked_at IS
    'KST 벽시계 (ADR-0010). NOT NULL 이면 재호출하지 않는다 — 배치 멱등의 근거이자, 호출 1건당 정확히 1회를 보장하는 유일한 수단';
