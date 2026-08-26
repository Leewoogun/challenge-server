-- V10__user_stats_loss_streak.sql
-- challenge-result feature: user_stats 에 **연패** 두 컬럼을 추가한다.
--
-- ============================================================================
-- ⚠️ 지금 이 값을 읽는 화면이 하나도 없다. wire 에도 노출하지 않는다.
--    (RecordData DTO 무변경 / GET /record 응답 무변경 — WireShapeContractTest 가 고정한다)
-- ============================================================================
--
-- ── 왜 필요한가 ────────────────────────────────────────────────────────────
--
-- 개돼지 랭킹(기획 §3.4)이 **총 패배 수 · 연패 · 패율** 로 정렬하는데, user_stats 에는
-- 연패에 대응하는 컬럼이 없었다. V1 이 만든 streak 두 컬럼(current_streak / max_streak)은
-- **연승 전용**이다 (아래 ⚠️ 참조).
--
-- ── 🔴 랭킹 feature 가 아니라 판정 feature 에서 넣는 이유: 비용의 *종류* ────────────
--
-- 지금은 집계 로직(UserRecord.applying)을 **처음 쓰는 시점**이라 컬럼 추가 + 규칙 2줄이면
-- 끝난다. 나중에 넣으면 **이미 쌓인 결과를 completed_at 순으로 되짚는 백필**이 필요하다 —
-- 연패는 **순서 의존**이라 현재 합계(wins/losses/draws)로는 산출할 수 없기 때문이다.
-- "총 몇 번 졌나" 는 합계에서 나오지만 "연속 몇 번 졌나" 는 사건의 순서를 알아야 나온다.
--
-- 즉 미루면 비용이 커지는 게 아니라 **비용의 종류가 바뀐다** (스키마 변경 → 데이터 복구).
-- DEFAULT 0 이라 기존 row 도 안전하다 — NOT NULL 이지만 백필 없이 즉시 유효하다.
-- (이 시점의 user_stats 는 판정 배치가 이제 막 채우기 시작한 테이블이라 실害도 없다.)
--
-- ── ⚠️ current_streak 의 부호를 뒤집어 재활용하지 않은 이유 ──────────────────────
--
-- "current_streak 에 음수를 넣어 연패를 표현" 이 가장 먼저 떠오르는 절약안이고, **틀렸다.**
--
--   1. 홈 StatsBar 가 그 값을 **"연승"** 라벨로 그리고, HomeUiState 가 `> 0` 일 때
--      "N🔥" 로 표기한다. 음수가 들어가면 화면이 조용히 거짓말을 한다.
--   2. max_streak("최대 연승")의 의미도 무너진다 — 음수를 섞은 값의 최대치는
--      연승도 연패도 아닌 무의미한 숫자가 된다.
--
-- 컬럼 둘을 더 쓰는 비용보다 "한 컬럼이 두 가지를 뜻한다" 의 비용이 크다.
--
-- ── 집계 규칙 (UserRecord.applying 이 이행. KDoc 에 근거 있음) ──────────────────
--
--   LOSE       → +1        BOTH_LOSE → +1   (양측 패도 패배다. losses 와 같은 사건 집합)
--   WIN        →  0        DRAW      →  0   (무승부가 끊는다 — 연승 규칙의 정확한 거울상)
--
--   max_loss_streak = max(max_loss_streak, current_loss_streak)  — 줄지 않는다.

ALTER TABLE user_stats ADD COLUMN current_loss_streak INT NOT NULL DEFAULT 0;
ALTER TABLE user_stats ADD COLUMN max_loss_streak     INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN user_stats.current_loss_streak IS
    '가장 최근 결과 시점부터 패배(LOSE/BOTH_LOSE)만 연속된 횟수. 승리와 무승부가 0 으로 끊는다. 개돼지 랭킹(기획 §3.4) 정렬 축 — 아직 읽는 화면 없음';

COMMENT ON COLUMN user_stats.max_loss_streak IS
    'current_loss_streak 의 역대 최고치. 줄지 않는다';

COMMENT ON COLUMN user_stats.current_streak IS
    '🔴 연승 전용이다. WIN 만 잇고 LOSE/DRAW/BOTH_LOSE 는 0 으로 끊는다. 연패는 음수가 아니라 current_loss_streak 에 있다 (홈 StatsBar 가 이 값을 "연승" 으로 그린다)';

COMMENT ON COLUMN user_stats.max_streak IS
    'current_streak 의 역대 최고치 = 최대 **연승**. 줄지 않는다';
