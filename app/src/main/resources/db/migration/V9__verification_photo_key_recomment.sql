-- V9__verification_photo_key_recomment.sql
-- challenge-verification feature (T-B2): verifications.photo_url 이 실제로 담는 값을 못박는다.
--
-- ============================================================================
-- ⚠️ 데이터도 DDL 도 바꾸지 않는다. 주석만 고친다.
--    V6__datetime_kst_convention.sql / V8__notification_type_recomment.sql 과 같은 성격이다.
-- ============================================================================
--
-- ── 스키마 변경이 없는 이유 ─────────────────────────────────────────────────
--
-- verifications 는 V1 + V4 로 **이미 완성돼 있다.**
--   status VARCHAR(20) NOT NULL DEFAULT 'PENDING'  (V4)
--   photo_url TEXT NULL                            (V4 에서 nullable 화)
--   verified_at TIMESTAMP NULL                     (V4 에서 nullable 화)
--   created_at TIMESTAMP NOT NULL                  (V4 추가)
--   UNIQUE (challenge_id, user_id)                 (V1)
--
-- V4 가 이 feature 를 정확히 내다보고 만들어졌다 — 주석에 "V1 은 photo_url NOT NULL 이라
-- '아직 인증 안 함'(PENDING) 표현이 불가능했다" 고 적혀 있다. 그래서 **DDL 이 0건**이다.
--
-- ── 그런데 컬럼 이름이 거짓말을 하게 됐다 ───────────────────────────────────
--
-- 🔴 이 컬럼에 들어가는 값은 **URL 이 아니라 PhotoStorage 의 key** 다.
--
--     예: '7/12/9f3c8a1b2e4d5f60a1b2c3d4e5f60718.jpg'
--
-- URL 을 저장하지 않는 이유가 둘이다 (ADR-0011):
--
--   1. **발급되는 URL 에는 만료가 있다.** 저장하면 만료된 URL 이 DB 에 영구히 남는다.
--      조회 때마다 새로 발급하는 것이 옳고, 그러려면 저장할 것은 key 다.
--   2. **URL 에는 host 가 박혀 있다.** 로컬 단계에서도 Android 에뮬레이터(10.0.2.2)와
--      iOS 시뮬레이터(localhost)가 같은 서버를 다른 이름으로 부르고, 오브젝트 스토리지로
--      이사하면 옛 row 의 URL 이 전부 죽는다. key 는 저장소가 바뀌어도 같은 사진을 가리킨다.
--
-- 컬럼 이름을 photo_key 로 바꾸는 쪽이 더 정직하지만, **개명은 엔티티·도메인·홈 피드 매핑까지
-- 건드리는 변경**이라 이 feature 범위 밖으로 뒀다. 대신 주석에서 못박는다 —
-- `\d+ verifications` 로 바로 보이는 자리이고, V8 이 같은 이유로 낡은 주석을 정정했다.
-- (soul-oath 의 `_signature_url` → `_signature_data` 개명이 선례이므로, 개명은 나중에
--  별도로 할 수 있다. 그때 이 주석이 근거가 된다.)
--
-- ── 기존 데이터 ────────────────────────────────────────────────────────────
--
-- 이 feature 이전에는 photo_url 을 채우는 코드 경로가 **존재하지 않았다**
-- (verification 엔드포인트 0건 / 업로드 인프라 0건 — spec §0.3).
-- 수락 시 만들어지는 row 는 전부 PENDING + photo_url NULL 이므로 **정정할 DML 이 없다.**

COMMENT ON COLUMN verifications.photo_url IS
    'PhotoStorage 의 key 다. URL 이 아니다 — 예: 7/12/9f3c...jpg. 읽기 URL 은 조회 시점에 만료를 실어 새로 발급한다(만료·host 를 DB 에 박제하지 않기 위함) — ADR-0011 / challenge-verification';

COMMENT ON COLUMN verifications.status IS
    'VerificationStatus enum name. PENDING(수락 시 생성, 아직 미인증) / VERIFIED(사진 제출 완료) / FAILED(마감까지 미인증 — 판정 배치 feature 소관, 아직 전이시키는 코드가 없다) — challenge-verification';

COMMENT ON COLUMN verifications.verified_at IS
    'VERIFIED 로 전이한 시각 (KST 벽시계 — ADR-0010). PENDING/FAILED 면 NULL. 판정 feature 가 이 값을 읽는다';
