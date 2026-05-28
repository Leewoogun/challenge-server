-- V3__users_refresh_token.sql
-- Refresh Token Rotation (1기기 1세션).
--
-- users 테이블에 현재 유효한 refresh 토큰의 sha256(hex) 와 발급 시각을 저장한다.
-- 두 컬럼 모두 nullable.
--   - V1 시기에 가입한 기존 row 는 hash 가 비어 있음. 다음 refresh 호출 시 hash 불일치로 401 →
--     클라이언트가 카카오 재로그인을 수행하면 그때부터 정상 rotation.
--   - 명시적 로그아웃 / 강제 무효화 시 NULL 로 되돌릴 수 있어야 한다 (향후 logout 구현).
--
-- 컬럼 길이 VARCHAR(64) — sha256 hex 고정 길이.

ALTER TABLE users
    ADD COLUMN refresh_token_hash VARCHAR(64);

ALTER TABLE users
    ADD COLUMN refresh_token_issued_at TIMESTAMP;
