-- `verifications.verified_at` 컬럼 주석 정정 (ai-verification). **DML 0건 · DDL 0건 — 주석만.**
--
-- 🔴 V9 의 주석이 이 feature 의 변경으로 **거짓이 됐다.**
--
--    V9 원문: 'VERIFIED 로 전이한 시각 (KST 벽시계 — ADR-0010). PENDING/FAILED 면 NULL. ...'
--                                                              ^^^^^^^^^^^^^^^^^^^^^^
--    AI 판별이 `VERIFIED → FAILED` 전이를 만들면서 **`FAILED` 인데 `verified_at` 이 남아 있는
--    row** 가 생겼다. 반려는 제출 사실을 지우지 않는다(ADR-0013 §결정 2 — AI 는 승패도
--    제출 이력도 건드리지 않고 사진 1장의 pass/fail 만 답한다).
--
-- ⚠️ **이 주석은 아무 코드도 깨뜨리지 않으면서 가장 권위 있는 자리에서 거짓을 말한다.**
--    다음 사람이 컬럼 주석을 읽고 *"FAILED 면 null 이구나"* 하면, 그 전제 위에 서 있는
--    `VerificationService.view()` 의 `photoDeleted` 판정(`verifiedAt != null && key == null &&
--    owner DELETED`)을 **버그로 읽고 되돌린다.** 그게 이 마이그레이션의 존재 이유다.
--
-- 🔵 V9__verification_photo_key_recomment.sql 이 이 패턴의 선례다(주석만 고치는 마이그레이션).
--    V13 을 직접 고친 것과 판단이 다른 이유는 명확하다 — **V9 는 이미 커밋돼 배포 이력에 있다.**

COMMENT ON COLUMN verifications.verified_at IS
    '제출(교체) 확정 시각 — KST 벽시계 (ADR-0010). VerificationService.submit 만이 쓴다. '
    '🔴 "VERIFIED 로 전이한 시각" 으로 읽지 마라 — status 와 독립이다. '
    'PENDING(미제출) 이면 NULL. VERIFIED 면 non-NULL. '
    '🔴 FAILED 는 두 가지다: 미제출로 마감(NULL) / AI 반려(non-NULL — 낸 적이 있으므로 남는다). '
    '교체하면 최종 제출 시각으로 갱신된다(last-write-wins, verification-photo-replace). '
    '회원탈퇴는 photo_url 만 NULL 로 밀고 이 값은 보존한다(mypage T-B2). '
    '읽는 곳: 판정 배치 / VerificationService.view 의 photoDeleted 판정 / 앱 사진 캐시 키(photoUrl + verifiedAt)';
