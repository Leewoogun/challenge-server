#!/bin/bash
# dev-test-login 격리 검증 — **꺼진 상태** (challenge.dev.test-login.enabled 를 뺀 채 재기동한 뒤 실행)
#
# ════════ 이 스크립트의 위치가 scratchpad 가 아닌 이유 ════════
#   최초 작성분은 세션 scratchpad(/private/tmp/...)에 뒀다가 **소실됐다**(2026-08-06 확인).
#   pm-lead 가 "배포 준비 시점에 그대로 쓴다"고 보존을 지시한 산출물인데
#   **세션 임시 디렉터리는 그 수명을 감당하지 못한다.** 그래서 레포 안으로 옮겼다.
#   (커밋은 pm-lead 지시 대기 — 커밋되기 전까지는 여전히 유실 위험이 있다.)
#
# ════════ 무엇을 검증하나 ════════
#   dev-test-login 의 핵심 수용 기준은 **"꺼진 상태에서 존재하지 않는다"** 이고,
#   그게 401 이 아니라 **404** 여야 한다:
#     - 401 이면 모바일 Ktor `Auth(bearer)` 가 "access 만료" 로 보고 refresh 를 시도하다
#       `emitSessionExpired()` 를 쏴 **로그인 화면을 리셋한다.** 경로가 없을 뿐인데
#       앱이 "세션 만료" 라고 **적극적으로 틀린 결론**을 낸다.
#     - 실제 노출을 막는 것은 permitAll 이 아니라 **빈이 존재하지 않는다는 사실**이다.
#       permitAll 은 상시이므로, 빈이 없으면 핸들러가 없어 404 로 떨어진다.
#
#   🔴 401 / 500 / 404 를 **각각 별도 단언**으로 둔다. 실패 시 원인이
#      Security(401) 인지 핸들러 부재(500) 인지 갈리기 때문이다.
#
# ════════ 사전 조건 ════════
#   application.yml 또는 환경변수에서 `challenge.dev.test-login.enabled` 를 **제거**하고 재기동.
#   (`false` 로 두어도 동작은 같다 — 커스텀 Condition 이 문자열 "true" 정확 비교라 그 외는 전부 꺼짐)
set -u
BASE=http://localhost:8080
PASS=0; FAIL=0
chk() { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf '  ✅ %s\n' "$1"
        else FAIL=$((FAIL+1)); printf '  ❌ %s\n     기대=%s\n     실제=%s\n' "$1" "$2" "$3"; fi; }
code() { curl -s -o /dev/null -w '%{http_code}' -m 10 "$@"; }

POST_TL=(-X POST "$BASE/api/v1/auth/test-login" -H 'Content-Type: application/json' -d '{"testUserNo":3}')

echo "════════ 1. test-login 이 사라졌는가 ════════"
TL=$(code "${POST_TL[@]}")
chk "test-login → 404 (존재하지 않음)" "404" "$TL"
# 아래 둘은 위와 같은 값을 다르게 진술한다 — 실패 시 원인을 가르기 위해 일부러 분리했다.
chk "test-login 이 401 이 아니다 (Security 가 먼저 끊지 않는다 = permitAll 상시)" "no401" "$([ "$TL" = "401" ] && echo 401 || echo no401)"
chk "test-login 이 500 이 아니다 (핸들러 부재가 500 으로 새지 않는다 = T-B1b)" "no500" "$([ "$TL" = "500" ] && echo 500 || echo no500)"

echo; echo "════════ 2. 문서에도 안 보이는가 ════════"
DOCS=$(curl -s -m 10 "$BASE/v3/api-docs")
chk "/v3/api-docs 에 test-login 경로 없음" "0" "$(printf '%s' "$DOCS" | grep -c 'auth/test-login' || true)"

echo; echo "════════ 3. 404 는 permitAll 경로에서만 나온다 (설계 확인) ════════"
#
# 🔴 여기가 이 feature 설계의 핵심이라 단언을 둘로 갈라 둔다.
#
#   permitAll 이고 핸들러 없음   →  404  (Security 를 통과해 라우팅까지 가서 못 찾음)
#   permitAll 아니고 핸들러 없음 →  401  (Security 가 라우팅 전에 끊음 — 경로 존재 여부와 무관)
#
#   test-login 이 404 가 되는 건 "핸들러가 없어서" 만이 아니라
#   **permitAll 이 상시라 Security 를 통과하기 때문**이다. 둘이 함께여야 성립한다.
#   그래서 permitAll 아닌 경로가 401 인 것은 **정상이고, 그대로 401 이어야 한다.**
#
#   ⚠️ 최초 작성분은 이 줄을 "임의 미등록 경로 → 404" 로 단언했다. **틀렸다**
#      (2026-08-06 실측: 401). 리포트 산문을 그대로 옮기다 permitAll 조건을 뭉갠 것이다.
chk "/actuator/health → 404 (permitAll + 핸들러 없음)" "404" "$(code "$BASE/actuator/health")"
chk "permitAll 아닌 미등록 경로 → 401 (Security 가 먼저 끊는 게 정상)" "401" "$(code "$BASE/api/v1/auth/nonexistent")"

echo; echo "════════ 4. permitAll 추가가 다른 걸 열지 않았는가 ════════"
# 🔴 이게 없으면 "404 를 만들려고 뭔가를 열어버린" 회귀를 못 잡는다.
chk "인증 필요 경로 /users/me → 401" "401" "$(code "$BASE/api/v1/users/me")"
chk "인증 필요 경로 /friends → 401" "401" "$(code "$BASE/api/v1/friends")"
chk "인증 필요 경로 /challenges/received → 401" "401" "$(code "$BASE/api/v1/challenges/received")"

echo; echo "════════════════════════════════════"
printf '  PASS=%d  FAIL=%d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
