#!/bin/bash
# soul-oath 실서버 검증 — 서버 재기동(V7 적용) 후 실행
#
# ⚠️ 아직 실행하지 않았다. pm-lead 지시: 모바일 #21 완료 후 사용자가 재기동할 때 한 번에 돌린다.
#
# ════════ 🔒 안전 제약 (사용자 실사용 데이터 보호) ════════
#   - `users` 에 DELETE/UPDATE/TRUNCATE 를 **한 줄도 쓰지 않는다.**
#   - **테스터1/테스터2 로 로그인하지 않는다** (기본 모드). test-login 호출은 ADR-0009 rotation 을
#     일으켜 `refresh_token_hash` 를 덮어쓰고, 사용자가 그 계정으로 앱에 로그인해 있으면
#     **진행 중인 손 검증 세션이 끊긴다.** → 기본 검증은 **테스터3** 으로만 수행한다.
#   - 사용자가 손으로 만든 `friendships` 행은 **읽지도 쓰지도 않는다.** 이 스크립트가 넣는
#     friendship 은 **RETURNING id 로 받은 그 id 하나만** 지운다(넣지 않았으면 안 지운다).
#   - challenges/contracts 의 삭제는 **취소 엔드포인트가 하는 일 그 자체**다(물리 삭제).
#     그게 이 하네스의 핵심 검증 대상이라 SQL 이 아니라 API 로만 일어난다.
#
# ════════ 커버 / 미커버 ════════
#   커버(기본): V7 적용·주석 / 생성+계약서 / TEXT 바이트 왕복 / 상세 응답 / 서명 검증 4종 /
#              최악값 20,150자 / **취소 FK 경로** / 미인증
#   미커버(기본): 수락 경로 — 상대 토큰이 필요하다. `SECOND=1` 또는 `SECOND=2` 로 명시적 opt-in.
#              ⚠️ 그 계정의 세션이 끊긴다. 사용자가 그 계정으로 앱에 로그인해 있지 않을 때만 켜라.
set -u
BASE=http://localhost:8080/api/v1
export PGPASSWORD=postgres
PSQL="psql -h localhost -U postgres -d challenge -tAc"
PASS=0; FAIL=0
SECOND="${SECOND:-}"
INSERTED_FS=""

chk() { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf '  ✅ %s\n' "$1"
        else FAIL=$((FAIL+1)); printf '  ❌ %s\n     기대=%s\n     실제=%s\n' "$1" "$2" "$3"; fi; }
chkne() { if [ "$2" != "$3" ]; then PASS=$((PASS+1)); printf '  ✅ %s\n' "$1"
        else FAIL=$((FAIL+1)); printf '  ❌ %s\n     달라야 하는데 같다=%s\n' "$1" "$3"; fi; }
# 경로를 argv 로 넘긴다 — 문자열 안에 따옴표를 중첩하면 Python SyntaxError 가 난다(과거 오진 원인).
jq_() { python3 -c 'import sys,json
d=json.load(sys.stdin)
try: print(eval("d"+sys.argv[1]))
except Exception as e: print("<추출실패:%s>" % e)' "$1" 2>/dev/null; }
login() { curl -s -m 10 -X POST "$BASE/auth/test-login" -H 'Content-Type: application/json' -d "{\"testUserNo\":$1}"; }

cleanup() {
  if [ -n "$INSERTED_FS" ]; then
    $PSQL "DELETE FROM friendships WHERE id=$INSERTED_FS;" >/dev/null
    echo "  🧹 이 스크립트가 넣은 friendship id=$INSERTED_FS 만 삭제"
  fi
}
trap cleanup EXIT

# 서명 픽스처. SIG 는 최소 유효 서명, WORST 는 모바일 캡처 상한(2,000점/64획)의 최악 인코딩.
SIG='{"v":1,"g":1000,"s":[[123,456,130,460],[700,200,705,210]]}'
WORST=$(python3 -c '
n,k=2000,64
per=[n//k+(1 if i<n%k else 0) for i in range(k)]
s="["+",".join("["+",".join(["1000,1000"]*m)+"]" for m in per)+"]"
print("{\"v\":1,\"g\":1000,\"s\":"+s+"}", end="")')
# JSON 문자열 필드로 박기 위한 이스케이프 (계약 §4.4 — signature 는 객체가 아니라 문자열이다)
esc() { python3 -c 'import sys,json;print(json.dumps(sys.stdin.read()),end="")'; }
SIG_F=$(printf '%s' "$SIG"   | esc)
WORST_F=$(printf '%s' "$WORST" | esc)

echo "════════ 0. V7 마이그레이션 ════════"
chk "flyway 최신 버전 = 7" "7" "$($PSQL "SELECT max(version::int) FROM flyway_schema_history WHERE success;")"
chk "challenger_signature_data 컬럼 존재" "1" "$($PSQL "SELECT count(*) FROM information_schema.columns WHERE table_name='contracts' AND column_name='challenger_signature_data';")"
chk "opponent_signature_data 컬럼 존재" "1" "$($PSQL "SELECT count(*) FROM information_schema.columns WHERE table_name='contracts' AND column_name='opponent_signature_data';")"
chk "_url 컬럼은 사라졌다" "0" "$($PSQL "SELECT count(*) FROM information_schema.columns WHERE table_name='contracts' AND column_name LIKE '%signature_url';")"
# 🔴 7 이다. V7 이 다는 건 6 개지만 V6 이 이미 contracts.created_at 에 달아뒀다
#    (V6 의 *_signed_at 2건은 V7 이 soul-oath 문구로 덮어썼다).
#    1차 실행에서 6 으로 단언했다가 틀렸다 — **내 변경분 개수를 세고 최종 상태를 안 봤다.**
chk "컬럼 주석 7건 (V7 6 + V6 created_at)" "7" "$($PSQL "SELECT count(*) FROM information_schema.columns c JOIN pg_class t ON t.relname=c.table_name WHERE c.table_name='contracts' AND col_description(t.oid, c.ordinal_position) IS NOT NULL;")"
# 개수만 세면 "어느 컬럼인지" 가 안 잡힌다 — soul-oath 가 단 6건을 이름으로 확인한다.
chk "soul-oath 주석 6건이 이름으로 확인된다" "6" "$($PSQL "SELECT count(*) FROM information_schema.columns c JOIN pg_class t ON t.relname=c.table_name WHERE c.table_name='contracts' AND col_description(t.oid, c.ordinal_position) LIKE '%soul-oath%';")"
chk "서명 컬럼 주석에 저장 형식이 들어 있다" "1" "$($PSQL "SELECT count(*) FROM information_schema.columns c JOIN pg_class t ON t.relname=c.table_name WHERE c.table_name='contracts' AND c.column_name='challenger_signature_data' AND col_description(t.oid, c.ordinal_position) LIKE '%\"v\":1,\"g\":1000%';")"
chk "FK 에 ON DELETE 절이 없다 (CASCADE 미사용)" "a" "$($PSQL "SELECT confdeltype FROM pg_constraint WHERE conname='contracts_challenge_id_fkey';")"

echo; echo "════════ 0-B. 사전 상태 (건드리면 안 되는 것) ════════"
BEFORE_USERS=$($PSQL "SELECT count(*) FROM users;")
BEFORE_FS=$($PSQL "SELECT count(*) FROM friendships;")
echo "  users=$BEFORE_USERS  friendships=$BEFORE_FS"

R=$(login 3); T3=$(printf '%s' "$R" | jq_ "['data']['accessToken']"); U3=$(printf '%s' "$R" | jq_ "['data']['userId']")
chk "테스터3 로그인" "200" "$(printf '%s' "$R" | jq_ "['code']")"

# 🔴 상대 선정은 SECOND 와 반드시 일치해야 한다.
#    §6 은 상대 토큰으로 수락하는데, 챌린지를 다른 계정 앞으로 만들면 서버가 정당하게
#    "내가 받은 도전장이 아니에요"(700) 로 막는다 — 권한 가드가 도는 것이지 결함이 아니다.
#    (1차 실행에서 실제로 이걸 밟았다. OPP=테스터1 인데 SECOND=2 로 수락을 시도했다.)
if [ -n "$SECOND" ]; then
  OPP=$($PSQL "SELECT id FROM users WHERE kakao_id = 99900000$SECOND;")
  echo "  상대(opponent) = user id $OPP (테스터$SECOND)  ← SECOND 와 일치. §6 에서 이 계정으로 로그인한다"
else
  OPP=$($PSQL "SELECT id FROM users WHERE kakao_id BETWEEN 999000001 AND 999000002 ORDER BY id LIMIT 1;")
  echo "  상대(opponent) = user id $OPP  ← 로그인하지 않는다 (§6 건너뜀)"
fi
[ -n "$OPP" ] || { echo "  ❌ 상대 계정을 찾지 못했다. 중단."; exit 1; }

FS=$($PSQL "SELECT id FROM friendships WHERE status='ACCEPTED' AND ((requester_id=$U3 AND receiver_id=$OPP) OR (requester_id=$OPP AND receiver_id=$U3)) LIMIT 1;")
if [ -z "$FS" ]; then
  INSERTED_FS=$($PSQL "INSERT INTO friendships (requester_id, receiver_id, status, created_at, accepted_at) VALUES ($U3,$OPP,'ACCEPTED',now(),now()) RETURNING id;" | head -1)
  echo "  ➕ friendship 신규 삽입 id=$INSERTED_FS (종료 시 이 id 만 삭제)"
else
  echo "  ♻️  기존 friendship id=$FS 재사용 (삽입/삭제 없음)"
fi

echo; echo "════════ 1. 신청 + 챌린저 서명 ════════"
R=$(curl -s -m 10 -X POST "$BASE/challenges" -H "Authorization: Bearer $T3" -H 'Content-Type: application/json' \
     -d "{\"opponentId\":$OPP,\"myMission\":\"운동 1시간\",\"betContent\":\"커피 사기\",\"deadlineType\":\"TODAY\",\"signature\":$SIG_F}")
echo "  raw: $R"
chk "code=200" "200" "$(printf '%s' "$R" | jq_ "['code']")"
chk "status=PENDING" "PENDING" "$(printf '%s' "$R" | jq_ "['data']['status']")"
CID=$(printf '%s' "$R" | jq_ "['data']['challengeId']")

chk "contracts row 1건 생성" "1" "$($PSQL "SELECT count(*) FROM contracts WHERE challenge_id=$CID;")"
chk "is_finalized=false" "f" "$($PSQL "SELECT is_finalized FROM contracts WHERE challenge_id=$CID;")"
chk "상대 서명은 NULL" "" "$($PSQL "SELECT coalesce(opponent_signature_data,'') FROM contracts WHERE challenge_id=$CID;")"
chk "상대 서명 시각도 NULL" "" "$($PSQL "SELECT coalesce(opponent_signed_at::text,'') FROM contracts WHERE challenge_id=$CID;")"

echo; echo "  ── 🔴 TEXT 컬럼에 바이트가 그대로 들어갔는가 ──"
chk "DB 저장값 == 보낸 문자열" "$SIG" "$($PSQL "SELECT challenger_signature_data FROM contracts WHERE challenge_id=$CID;")"

echo; echo "  ── 계약서 본문 (T-B2b) ──"
CONTENT=$($PSQL "SELECT content FROM contracts WHERE challenge_id=$CID;")
echo "$CONTENT" | sed 's/^/    | /'
chk "조사 병기 없음" "0" "$(printf '%s' "$CONTENT" | grep -c '와(과)' || true)"
chk "수락 전이라 자리표시가 있다" "1" "$(printf '%s' "$CONTENT" | grep -c '(수락 시 기재)' || true)"
chk "마감이 24:00 표기" "1" "$(printf '%s' "$CONTENT" | grep -c '24:00' || true)"
chk "마감에 00:00 이 아니다" "0" "$(printf '%s' "$CONTENT" | grep -c '마감:.*00:00' || true)"

echo; echo "════════ 2. 상세 조회 — 응답이 문자열인가 (T-B2a) ════════"
R=$(curl -s -m 10 "$BASE/challenges/$CID" -H "Authorization: Bearer $T3")
echo "  raw: $R"
chk "code=200" "200" "$(printf '%s' "$R" | jq_ "['code']")"
chk "challenger/opponent 를 역할 그대로 준다" "$U3" "$(printf '%s' "$R" | jq_ "['data']['challenger']['userId']")"
chk "상대 미션은 수락 전이라 null" "None" "$(printf '%s' "$R" | jq_ "['data']['opponent']['mission']")"
chk "🔴 응답 서명 == DB 값 (바이트 동일)" "$SIG" "$(printf '%s' "$R" | jq_ "['data']['contract']['challengerSignature']")"
chk "🔴 서명이 JSON 문자열 타입이다 (객체 아님)" "str" "$(printf '%s' "$R" | python3 -c "import sys,json;print(type(json.load(sys.stdin)['data']['contract']['challengerSignature']).__name__)")"
chk "미서명 필드는 null" "None" "$(printf '%s' "$R" | jq_ "['data']['contract']['opponentSignature']")"
chk "isFinalized=false" "False" "$(printf '%s' "$R" | jq_ "['data']['contract']['isFinalized']")"

echo; echo "════════ 3. 서명 검증 (쓰기 시점) ════════"
try_create() { curl -s -m 20 -X POST "$BASE/challenges" -H "Authorization: Bearer $T3" -H 'Content-Type: application/json' \
     -d "{\"opponentId\":$OPP,\"myMission\":\"운동\",\"betContent\":\"커피\",\"deadlineType\":\"TOMORROW\",\"signature\":$1}"; }
EMPTY_F=$(printf '%s' '{"v":1,"g":1000,"s":[]}' | esc)
chk "빈 서명 → 700" "700" "$(printf '%s' "$(try_create "$EMPTY_F")" | jq_ "['code']")"
chk "빈 서명 문구" "서명을 해주세요" "$(printf '%s' "$(try_create "$EMPTY_F")" | jq_ "['message']")"
ODD_F=$(printf '%s' '{"v":1,"g":1000,"s":[[1,2,3]]}' | esc)
chk "홀수 길이 획 → 700" "700" "$(printf '%s' "$(try_create "$ODD_F")" | jq_ "['code']")"
chk "홀수 길이 문구" "서명 형식이 올바르지 않습니다" "$(printf '%s' "$(try_create "$ODD_F")" | jq_ "['message']")"
V2_F=$(printf '%s' '{"v":2,"g":1000,"s":[[1,2]]}' | esc)
chk "알 수 없는 버전 → 700" "700" "$(printf '%s' "$(try_create "$V2_F")" | jq_ "['code']")"
HUGE_F=$(python3 -c 'print("\"" + "x"*40000 + "\"", end="")')
chk "40KB → 700 (상한 초과)" "700" "$(printf '%s' "$(try_create "$HUGE_F")" | jq_ "['code']")"
chk "상한 초과 문구" "서명 데이터가 너무 큽니다" "$(printf '%s' "$(try_create "$HUGE_F")" | jq_ "['message']")"
chk "거부된 요청이 챌린지를 만들지 않았다" "1" "$($PSQL "SELECT count(*) FROM challenges WHERE challenger_id=$U3;")"

echo; echo "  ── 🔴 최악값(모바일 캡처 상한)이 실서버에서 통과하는가 ──"
echo "  최악값 길이 = ${#WORST} 자 (모바일 실측 20,150 / 서버 상한 32,768)"
chk "최악값 길이가 20,150 자" "20150" "${#WORST}"
R=$(try_create "$WORST_F")
chk "최악값 서명으로 생성 성공" "200" "$(printf '%s' "$R" | jq_ "['code']")"
CID2=$(printf '%s' "$R" | jq_ "['data']['challengeId']")
chk "🔴 최악값도 DB 왕복 무손실" "$WORST" "$($PSQL "SELECT challenger_signature_data FROM contracts WHERE challenge_id=$CID2;")"

echo; echo "════════ 4. 🔴 취소 — FK 경로 (이 feature 의 최대 위험) ════════"
chk "취소 전 contract 존재" "1" "$($PSQL "SELECT count(*) FROM contracts WHERE challenge_id=$CID2;")"
R=$(curl -s -m 10 -X DELETE "$BASE/challenges/$CID2" -H "Authorization: Bearer $T3")
echo "  raw: $R"
chk "🔴 취소가 500 이 아니다 (FK 위반 없음)" "200" "$(printf '%s' "$R" | jq_ "['code']")"
chk "error=false" "False" "$(printf '%s' "$R" | jq_ "['error']")"
chk "challenge 물리 삭제됨" "0" "$($PSQL "SELECT count(*) FROM challenges WHERE id=$CID2;")"
chk "contract 도 함께 삭제됨" "0" "$($PSQL "SELECT count(*) FROM contracts WHERE challenge_id=$CID2;")"

echo; echo "════════ 5. 미인증 ════════"
chk "토큰 없이 상세 조회 → 401" "401" "$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$BASE/challenges/$CID")"
chk "토큰 없이 생성 → 401" "401" "$(curl -s -o /dev/null -w '%{http_code}' -m 10 -X POST "$BASE/challenges" -H 'Content-Type: application/json' -d '{}')"

if [ -n "$SECOND" ]; then
echo; echo "════════ 6. 수락 + 재렌더 (opt-in: SECOND=$SECOND — 해당 계정 세션이 끊긴다) ════════"
  R=$(login "$SECOND"); TS=$(printf '%s' "$R" | jq_ "['data']['accessToken']")
  R=$(curl -s -m 10 -X POST "$BASE/challenges/$CID/accept" -H "Authorization: Bearer $TS" -H 'Content-Type: application/json' \
       -d "{\"myMission\":\"책 30페이지 읽기\",\"signature\":$SIG_F}")
  echo "  raw: $R"
  chk "수락 code=200" "200" "$(printf '%s' "$R" | jq_ "['code']")"
  chk "status=IN_PROGRESS" "IN_PROGRESS" "$(printf '%s' "$R" | jq_ "['data']['status']")"
  chk "is_finalized=true" "t" "$($PSQL "SELECT is_finalized FROM contracts WHERE challenge_id=$CID;")"
  chk "상대 서명 저장 == 보낸 값" "$SIG" "$($PSQL "SELECT opponent_signature_data FROM contracts WHERE challenge_id=$CID;")"
  chk "verifications 2건" "2" "$($PSQL "SELECT count(*) FROM verifications WHERE challenge_id=$CID;")"

  echo "  ── 🔴 완결 시 본문 재렌더 (T-B2b 2) ──"
  NEW=$($PSQL "SELECT content FROM contracts WHERE challenge_id=$CID;")
  echo "$NEW" | sed 's/^/    | /'
  chk "자리표시가 사라졌다" "0" "$(printf '%s' "$NEW" | grep -c '(수락 시 기재)' || true)"
  chk "실제 상대 미션이 들어갔다" "1" "$(printf '%s' "$NEW" | grep -c '책 30페이지 읽기' || true)"
  chkne "본문이 신청 시점과 다르다" "$CONTENT" "$NEW"

  R=$(curl -s -m 10 -X POST "$BASE/challenges/$CID/accept" -H "Authorization: Bearer $TS" -H 'Content-Type: application/json' \
       -d "{\"myMission\":\"다른 미션\",\"signature\":$SIG_F}")
  chk "재서명 거부 → 705" "705" "$(printf '%s' "$R" | jq_ "['code']")"
  chk "재서명 거부 후 본문 불변" "$NEW" "$($PSQL "SELECT content FROM contracts WHERE challenge_id=$CID;")"
else
echo; echo "════════ 6. 수락 경로 — ⏭️  건너뜀 ════════"
  echo "  상대 토큰이 필요하고, 그 계정의 세션이 끊긴다(ADR-0009 rotation)."
  echo "  돌리려면: SECOND=2 bash soul-oath.sh   ← 해당 계정으로 앱에 로그인해 있지 않을 때만"
  echo "  ⚠️  이 구간을 안 돌리면 **완결 시 재렌더와 is_finalized 전환이 실서버에서 미검증**이다."
fi

echo; echo "════════ 7. 사용자 데이터 무결성 ════════"
chk "users 수 불변" "$BEFORE_USERS" "$($PSQL "SELECT count(*) FROM users;")"
echo "  ℹ️  friendships 는 종료 시 trap 이 정리한다 (넣었을 때만)"
echo "  ℹ️  남은 챌린지 id=$CID 은 손 검증용으로 남겨둔다 — 지우려면 앱에서 취소하거나:"
echo "     DELETE FROM verifications WHERE challenge_id=$CID; DELETE FROM contracts WHERE challenge_id=$CID; DELETE FROM challenges WHERE id=$CID;"

echo; echo "════════════════════════════════════"
printf '  PASS=%d  FAIL=%d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
