-- V11__user_withdrawal.sql
-- mypage feature (T-B2): 회원탈퇴를 위해 users.kakao_id 의 NOT NULL 을 푼다.
--
-- ============================================================================
-- ⚠️ DDL 은 한 줄뿐이다. 나머지는 전부 "왜 이 한 줄인가" 의 근거다.
--    V6/V8/V9 가 결정을 주석으로 박제한 선례를 따른다.
-- ============================================================================
--
-- ── 탈퇴 정책 (PM 스펙 확정) ────────────────────────────────────────────────
--
-- 탈퇴 = **익명화 + 기록 보존 + 사진 삭제**. row 를 지우지 않는다.
--
--   users 개인정보          → 익명화 (아래 목록)
--   카카오 연결             → unlink (admin key)
--   friendships             → 물리 삭제
--   보낸(challenger) PENDING 챌린지
--                           → 🔴 물리 삭제 (T-B4)
--   받은(opponent) PENDING 챌린지
--                           → 그대로 둔다 — 수락될 수 없어 자연 만료 (T-B4)
--   notifications           → 🔴 물리 삭제 (T-B4)
--   COMPLETED/IN_PROGRESS 챌린지·계약서·서명·결과·user_stats·friend_records·taunt_messages
--                           → 🔴 보존
--   verifications row       → 🔴 보존 (status / verified_at 그대로)
--   인증 사진 파일 + photo_url(=key)
--                           → 삭제 / NULL
--
-- ── 🔴 왜 PENDING 챌린지와 notifications 만 보존에서 빠지는가 (T-B4) ────────
--
-- 보존 원칙의 근거는 *"그 기록이 상대의 것이기도 하다"* 이다. 두 대상은 거기에 해당하지 않는다.
--
--   PENDING 챌린지: 계약서·서명·사진·결과가 하나도 없어 **보존할 증거가 0** 이다. 반대로
--   남겨 두면 상대가 탈퇴자의 도전장을 수락해 **결과가 이미 정해진 챌린지**가 시작된다
--   (탈퇴자는 로그인이 불가해 인증을 못 올린다). 챌린저가 직접 취소한 것과 결과가 같다.
--   단, 탈퇴자가 **받은** PENDING 은 아무도 수락할 수 없어 자연 만료되므로 건드리지 않는다 —
--   지우면 살아 있는 상대가 자기가 보낸 도전장을 잃는다.
--
--   notifications: 수신함은 **본인만 읽는 개인 데이터**라 양자 기록이 아니고
--   **개인정보 삭제 축**에 속한다 (pm-lead 판정).
--
-- ⚠️ 둘 다 DDL 변경이 없다 — 애플리케이션이 DELETE 로 처리한다. 이 파일에 기록하는 이유는
--    탈퇴 정책의 단일 출처가 여기이기 때문이다.
--
-- 익명화되는 컬럼은 정확히 이것들이다 (더도 덜도 아니다):
--   kakao_id NULL / nickname '탈퇴한 사용자' / profile_image_url NULL /
--   phone_number NULL / phone_verified false / fcm_token NULL /
--   refresh_token_hash NULL / refresh_token_issued_at NULL /
--   status 'DELETED' / updated_at 현재 KST.
-- id 와 created_at 은 건드리지 않는다.
--
-- ── 🔴 왜 kakao_id 를 NULL 로 만드는가 ──────────────────────────────────────
--
-- 그대로 두면 **탈퇴자가 카카오로 재로그인할 때 계정이 부활한다.**
-- `AuthService.loginWithKakao` 는 `findByKakaoId` 로 기존 row 를 찾아 그 위에 덮어쓰는데,
-- 익명화된 DELETED row 가 그대로 걸려든다 — 닉네임 "탈퇴한 사용자", 남의(=예전 자신의)
-- 전적과 챌린지를 그대로 물려받은 계정이 살아 돌아온다. kakao_id 가 NULL 이면
-- `findByKakaoId` 가 아무것도 찾지 못하고 **재로그인은 새 계정을 만든다.** 그게 맞는 동작이다.
--
-- 더불어 kakao_id 자체가 **카카오에서의 그 사람을 가리키는 식별자 = 개인정보**다.
-- 익명화한다면서 남겨 둘 이유가 없다.
--
-- ── 🔴 왜 sentinel(-id 같은 음수)이 아닌가 ──────────────────────────────────
--
-- NULL 은 *"카카오 연결이 없다"* 를 있는 그대로 표현한다. sentinel 은 읽는 사람이 규약을
-- 알아야만 해독되고, 그 규약은 반드시 어딘가에서 잊힌다. 표현할 수 있는 것을 인코딩하지 않는다.
--
-- ── 🔴 UNIQUE 제약은 그대로 둔다 ────────────────────────────────────────────
--
-- Postgres 는 UNIQUE 컬럼에 NULL 을 **여러 개** 허용한다 (NULL 은 서로 같지 않다).
-- 따라서 탈퇴자가 몇 명이 되든 kakao_id IS NULL 인 row 끼리 충돌하지 않는다.
-- 제약을 푸는 순간 살아 있는 계정의 중복 가입을 막는 장치가 사라지므로 **건드리면 안 된다.**

ALTER TABLE users ALTER COLUMN kakao_id DROP NOT NULL;

COMMENT ON COLUMN users.kakao_id IS
    '카카오 사용자 id. 탈퇴 시 NULL — unlink 후 연결이 사라지고, 남겨 두면 재로그인이 탈퇴 계정을 부활시킨다. UNIQUE 이지만 NULL 다중 허용이라 탈퇴자 N명이 공존한다 — mypage T-B2';

COMMENT ON COLUMN users.nickname IS
    '탈퇴 시 ''탈퇴한 사용자'' 로 덮인다. NOT NULL 을 유지하는 것이 계약이다 — 상대의 지난 챌린지 상세·보관함·홈 카드가 이 값을 non-null 로 받으므로, NULL 로 만들면 그 응답들이 전부 깨진다 — mypage T-B2';

COMMENT ON COLUMN users.status IS
    'ACTIVE / SUSPENDED / DELETED. DELETED = 탈퇴(익명화 완료). row 는 남는다 — 상대의 챌린지·계약서가 FK 로 이 row 를 가리키기 때문이다 — mypage T-B2';
