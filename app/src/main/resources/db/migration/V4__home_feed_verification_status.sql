-- V4__home_feed_verification_status.sql
-- home-feed feature: verifications 테이블에 PENDING/FAILED 상태 표현 추가.
--
-- V1__init.sql 의 verifications 는 photo_url NOT NULL / verified_at NOT NULL 이라
-- "아직 인증 안 함" (PENDING) 또는 "시간 내 인증 실패" (FAILED) 상태 표현이 불가능했다.
--
-- 변경:
--   1. status 컬럼 신규 추가 (default 'PENDING') — PENDING / VERIFIED / FAILED 3종
--   2. photo_url NOT NULL → nullable (PENDING/FAILED row 에는 사진이 없음)
--   3. verified_at NOT NULL → nullable (위와 동일 사유)
--   4. created_at 추가 — row 가 언제 만들어졌는지 (챌린지 IN_PROGRESS 전이 시점)
--   5. 기존 row (photo_url IS NOT NULL) 는 VERIFIED 로 백필
--
-- 마이그레이션 번호 V4 선택 이유:
--   현재 적용된 마이그레이션은 V1, V3 (V2 비어 있음). 새 마이그레이션을 V2 로 끼워넣으면
--   Flyway out-of-order 미허용 환경(기본값) 의 기존 로컬 DB에서 거부된다.
--   V4 로 두면 신규 DB / 기존 DB 모두 V1 → V3 → V4 순으로 정상 적용된다.

ALTER TABLE verifications
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING';

ALTER TABLE verifications
    ALTER COLUMN photo_url DROP NOT NULL;

ALTER TABLE verifications
    ALTER COLUMN verified_at DROP NOT NULL;

ALTER TABLE verifications
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE verifications
   SET status = 'VERIFIED'
 WHERE photo_url IS NOT NULL;
