-- V6__datetime_kst_convention.sql
-- datetime-model-migration feature (ADR-0010): 시간 컬럼의 해석 기준을 KST 로 확정한다.
--
-- ============================================================================
-- ⚠️ 이 마이그레이션은 데이터를 바꾸지 않는다. 그게 핵심이다.
-- ============================================================================
--
-- spec 초안은 "서버가 UTC 로 저장 중이니 users 1행의 시간 컬럼을 +9h 보정한다" 였다.
-- **틀린 전제였고, 그대로 실행했으면 멀쩡한 데이터를 손상시켰다.**
-- 다음 사람이 같은 오판을 반복하지 않도록 근거를 여기에 남긴다.
--
-- ── 실측 (2026-07-31) ───────────────────────────────────────────────────────
--
--   users 1행:
--     created_at              = 2026-05-07 10:10:42
--     updated_at              = 2026-07-28 11:01:23
--     refresh_token_issued_at = 2026-07-31 08:55:05
--   DB now()  = 2026-07-31 09:23:49   (Postgres TimeZone = Asia/Seoul)
--   UTC now() = 2026-07-31 00:23:49
--
-- **결정적 근거는 `refresh_token_issued_at` 이다.**
-- 저장값이 09:23 시점 기준 28분 전(08:55) 이다 — 그날 아침 로그인 시각과 정확히 맞는다.
-- 만약 이 값이 UTC 였다면 KST 로는 17:55, 즉 **미래**가 된다.
-- 리프레시 토큰이 미래에 발급될 수는 없으므로, 저장된 값은 **이미 KST** 다.
--
-- (created_at 5월 / updated_at 7월 만 봤다면 UTC 인지 KST 인지 구별할 근거가 없다.
--  "방금 발급됐다"는 의미를 가진 컬럼이라야 반증이 가능하다.)
--
-- ── 왜 이미 KST 였나 ────────────────────────────────────────────────────────
--
-- `UserEntity.createdAt/updatedAt` 와 `AuthService` 가 `LocalDateTime.now()` 를 쓰는데,
-- 이는 **JVM 기본 타임존**을 따른다. 개발 머신이 Asia/Seoul 이라 처음부터 KST 로 기록돼 왔다.
--
-- 즉 "DB TIMESTAMP 를 UTC 로 간주한다"는 **문서상 규약을 코드가 한 번도 지키지 않았다.**
-- challenge-create 가 그 규약을 충실히 따라 `Clock.systemUTC()` 를 도입하면서 오히려
-- **UTC 섬**이 생겼고, 같은 DB 에 9시간 다른 두 기준이 공존하게 됐다.
-- (challenges / verifications 는 row 0 이라 실害는 없었다. 그래서 지금이 닫을 적기다.)
--
-- ── 보정 대상이 없는 이유 ───────────────────────────────────────────────────
--
--   users        = 1행 → 이미 KST. 건드리면 손상.
--   friendships  = 0행
--   challenges   = 0행
--   verifications= 0행
--   user_stats   = 0행
--
-- 따라서 **DML 은 한 줄도 없다.** 대신 아래에서 컬럼 주석으로 해석 기준을 못박는다.
--
-- ============================================================================
-- 실제 변경: 시간 컬럼에 KST 해석 기준을 주석으로 기록
-- ============================================================================
--
-- spec 리스크 항목: "`LocalDateTime` 은 offset 을 잃는다 — '이 값은 KST' 라는 약속이
-- 타입이 아니라 문서와 관행에만 존재하게 된다."
--
-- 그 약속을 **DB 안**으로 옮긴다. `\d+ challenges` 나 information_schema 로 바로 보이므로,
-- 서버 코드를 안 읽는 사람(운영/분석/다음 담당자)도 기준을 알 수 있다.
-- 코드 동작에는 영향이 없다 (순수 메타데이터).

-- ── users ──
COMMENT ON COLUMN users.created_at              IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';
COMMENT ON COLUMN users.updated_at              IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';
COMMENT ON COLUMN users.refresh_token_issued_at IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';

-- ── friendships ──
COMMENT ON COLUMN friendships.created_at  IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';
COMMENT ON COLUMN friendships.accepted_at IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';

-- ── challenges ──
COMMENT ON COLUMN challenges.challenge_date IS 'KST 기준 날짜(DATE). 챌린지가 속한 "하루" 는 KST 로 끊는다 — ADR-0010';
COMMENT ON COLUMN challenges.deadline       IS 'KST(UTC+9) 기준. TODAY 선택 시 challenge_date 익일 00:00 KST — ADR-0010';
COMMENT ON COLUMN challenges.created_at     IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';
COMMENT ON COLUMN challenges.completed_at   IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';

-- ── verifications ──
COMMENT ON COLUMN verifications.verified_at IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';
COMMENT ON COLUMN verifications.created_at  IS 'KST(UTC+9) 기준. 챌린지 IN_PROGRESS 전이 시점 — ADR-0010';

-- ── user_stats ──
COMMENT ON COLUMN user_stats.updated_at IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';

-- ── 본 feature 비범위 테이블 (코드는 안 건드리되 해석 기준은 동일하게 문서화) ──
COMMENT ON COLUMN contracts.challenger_signed_at IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';
COMMENT ON COLUMN contracts.opponent_signed_at   IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';
COMMENT ON COLUMN contracts.created_at           IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';
COMMENT ON COLUMN taunt_messages.created_at      IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';
COMMENT ON COLUMN notifications.created_at       IS 'KST(UTC+9) 기준. offset 미저장 — ADR-0010';

-- 참고: flyway_schema_history.installed_on 은 Flyway 내부 컬럼이라 건드리지 않는다 (spec 비범위).
