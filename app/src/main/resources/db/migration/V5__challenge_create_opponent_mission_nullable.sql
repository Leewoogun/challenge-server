-- V5__challenge_create_opponent_mission_nullable.sql
-- challenge-create feature: challenges.opponent_mission 을 nullable 로 완화.
--
-- V1__init.sql 은 challenger_mission / opponent_mission 을 둘 다 NOT NULL 로 정의했다.
-- 이는 "챌린저가 양쪽 미션을 모두 정한다" 는 전제였는데, challenge-create spec 결정 2 에서
-- **opponent_mission 은 상대가 수락하는 시점에 직접 입력한다** 로 확정됐다.
--
-- 따라서 PENDING 상태(= 상대가 아직 응답 전)의 challenges row 는 opponent_mission 이 없다.
-- NOT NULL 을 유지하면 신청 시 빈 문자열/placeholder 를 넣어야 하고, "미입력" 과 "빈 미션" 을
-- 구분할 수 없게 된다.
--
-- 변경:
--   1. opponent_mission NOT NULL → nullable
--   2. 중복 챌린지 차단용 부분 유니크 인덱스 추가 (아래 2번 블록 주석 참조)
--
-- 기존 row 백필 불필요 — challenges 테이블은 아직 프로덕션 데이터가 없다
-- (home-feed 는 read-only, 본 feature 가 최초의 INSERT 경로).
--
-- 마이그레이션 번호 V5 선택 이유:
--   현재 적용된 마이그레이션은 V1, V3, V4 (V2 는 비어 있음 — V4 주석 참조).
--   V5 로 두면 신규 DB / 기존 DB 모두 V1 → V3 → V4 → V5 순으로 정상 적용된다.
--
-- verifications 테이블은 본 마이그레이션에서 건드리지 않는다.
--   수락 시 생성하는 PENDING row 는 V4 가 이미 photo_url / verified_at 을 nullable 화하고
--   status DEFAULT 'PENDING' + created_at 을 추가해 뒀으므로 추가 완화가 필요 없다.

-- ========== 1. opponent_mission nullable ==========

ALTER TABLE challenges
    ALTER COLUMN opponent_mission DROP NOT NULL;

-- ========== 2. 중복 챌린지 차단용 부분 유니크 인덱스 ==========
--
-- spec 수용 기준: "같은 상대 + 같은 challenge_date 에 PENDING/IN_PROGRESS 챌린지가 있으면 생성이 거부된다"
-- (양방향 — 내가 걸었든 상대가 걸었든. api-contract 오픈이슈 3, 2026-07-28 확정)
--
-- 애플리케이션 레벨 사전 검사만으로는 이 기준을 실제로 보장할 수 없다:
--   Postgres 기본 격리수준 READ COMMITTED 에서 동시 트랜잭션의 미커밋 INSERT 는 서로 보이지 않는다.
--   두 요청이 동시에 사전 검사를 통과하면 중복 row 2건이 그대로 커밋된다.
--   → DB 제약이 유일한 실질적 보증 수단이다.
--
-- LEAST/GREATEST 로 (challenger_id, opponent_id) 쌍을 정규화해 **방향 무관** 유일성을 만든다.
-- 두 함수 모두 IMMUTABLE 이라 인덱스 표현식으로 사용할 수 있다.
--
-- 부분 인덱스(WHERE 절)로 살아있는 챌린지만 대상으로 한다 — REJECTED / COMPLETED / EXPIRED 는
-- 같은 쌍·같은 날짜로 몇 건이 쌓여도 무방하고, 오히려 재도전을 막으면 안 된다.
--
-- 이 인덱스가 있어야 애플리케이션의 race 백업 catch(DataIntegrityViolationException → code 700)가
-- friends 2차 sendRequest 와 동일하게 실제로 동작한다. 제약이 없으면 그 catch 는 dead code 다.

CREATE UNIQUE INDEX uq_challenges_active_pair_date
    ON challenges (
        LEAST(challenger_id, opponent_id),
        GREATEST(challenger_id, opponent_id),
        challenge_date
    )
 WHERE status IN ('PENDING', 'IN_PROGRESS');
