#!/bin/bash
# challenge-server 배포 — Mac 에서 빌드 → ECR push → EC2 에서 app 컨테이너 교체.
#
# ════════ 사용법 ════════
#   scripts/deploy.sh                    현재 커밋을 빌드해 배포
#   scripts/deploy.sh --rollback <sha>   ECR 에 남아 있는 <sha> 태그 이미지로 되돌림 (빌드 안 함)
#   scripts/deploy.sh --allow-dirty      커밋 안 된 변경이 있어도 배포 (태그에 -dirty 가 붙는다)
#
# ════════ 사전 조건 ════════
#   - Docker 데몬 실행 중 (colima start)
#   - AWS CLI 로그인 (aws login) — 만료됐으면 스크립트가 알려 준다
#   - SSH 키 ~/.ssh/challenge-server.pem, 보안그룹 22 번에 현재 IP 가 허용돼 있을 것
#
# ════════ 무엇을 하나 ════════
#   1. 태그 = 짧은 커밋 해시. ECR 에는 <sha> 와 latest 두 태그로 올린다.
#      EC2 의 .env 는 APP_IMAGE=...:latest 고정이라, "latest 를 무엇으로 가리키느냐" 가 곧 배포 버전이다.
#      롤백도 ECR 에서 latest 를 옛 <sha> 로 옮겨 붙이는 것뿐이다 (수명 주기 정책상 최근 10개만 남는다).
#   2. EC2 에서는 app 만 pull/재생성한다 — postgres/redis/nginx 는 건드리지 않는다.
#   3. 컨테이너 healthcheck 가 healthy 가 될 때까지 기다리고, 실패하면 로그를 보여 주고 비정상 종료한다.
#
#   🔴 app 재생성 동안(기동 ~20초) 요청은 nginx 502 가 된다. 무중단 배포가 아니다.
set -euo pipefail

REGISTRY="${REGISTRY:-363183905996.dkr.ecr.ap-southeast-2.amazonaws.com}"
REPO="${REPO:-challenge-server}"
HOST="${HOST:-api.woo-challenge.shop}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/challenge-server.pem}"
REMOTE_DIR=/srv/challenge
IMAGE="$REGISTRY/$REPO"

ROLLBACK=""; ALLOW_DIRTY=false
while [ $# -gt 0 ]; do
  case "$1" in
    --rollback) ROLLBACK="${2:?--rollback 뒤에 태그(커밋 해시)를 주세요}"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY=true; shift ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
done

step() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m✖ %s\033[0m\n' "$*" >&2; exit 1; }
# accept-new: 처음 보는 호스트 키만 자동 등록하고, 이미 등록된 키가 바뀌었으면 거부한다(중간자 방어 유지).
#   BatchMode 에서는 확인 질문을 못 하므로 이게 없으면 첫 접속이 그냥 실패한다.
remote() { ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new "ec2-user@$HOST" "$@" 2>/dev/null; }

cd "$(dirname "$0")/.."

# ── 사전 점검 ───────────────────────────────────────────────
step "사전 점검"
aws sts get-caller-identity >/dev/null 2>&1 || die "AWS 자격 증명이 없거나 만료됐습니다. 'aws login' 후 다시 실행하세요."
remote true || die "SSH 접속 실패 ($HOST). 키 경로, 또는 보안그룹 22 번에 현재 IP($(curl -s https://checkip.amazonaws.com)) 가 허용됐는지 확인하세요."

if [ -n "$ROLLBACK" ]; then
  # ── 롤백: ECR 에서 latest 를 <sha> 로 옮겨 붙인다 ─────────────
  TAG="$ROLLBACK"
  step "롤백: latest → $TAG"
  MANIFEST=$(aws ecr batch-get-image --repository-name "$REPO" --image-ids imageTag="$TAG" \
    --query 'images[0].imageManifest' --output text)
  [ "$MANIFEST" != "None" ] && [ -n "$MANIFEST" ] || die "ECR 에 '$TAG' 태그가 없습니다. 'aws ecr list-images --repository-name $REPO' 로 확인하세요."
  aws ecr put-image --repository-name "$REPO" --image-tag latest --image-manifest "$MANIFEST" >/dev/null 2>&1 \
    || echo "  (latest 가 이미 $TAG 를 가리키고 있습니다)"
else
  # ── 빌드 & push ───────────────────────────────────────────
  docker info >/dev/null 2>&1 || die "Docker 데몬이 꺼져 있습니다. 'colima start' 후 다시 실행하세요."
  TAG=$(git rev-parse --short HEAD)
  if [ -n "$(git status --porcelain)" ]; then
    $ALLOW_DIRTY || die "커밋하지 않은 변경이 있습니다. 커밋 후 배포하거나 --allow-dirty 를 쓰세요."
    TAG="$TAG-dirty"
  fi

  step "JAR 빌드 (:app:bootJar)"
  ./gradlew :app:bootJar -q

  step "이미지 빌드 ($TAG, linux/arm64)"
  # --provenance/--sbom=false: attestation 이 붙으면 이미지 인덱스로 올라가 ECR 스캔이 안 되고
  # 수명 주기 정책 개수를 잡아먹는다 (deploy/README.md 참조).
  docker build --platform linux/arm64 --provenance=false --sbom=false \
    -t "$IMAGE:$TAG" -t "$IMAGE:latest" . >/dev/null

  step "ECR push"
  aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
  docker push -q "$IMAGE:$TAG" >/dev/null
  docker push -q "$IMAGE:latest" >/dev/null
fi

# ── EC2: app 교체 ─────────────────────────────────────────────
step "EC2 에서 app 교체"
remote "cd $REMOTE_DIR && docker compose pull -q app && docker compose up -d app" \
  || die "EC2 에서 compose 실행 실패"

step "healthcheck 대기"
STATUS=""
for _ in $(seq 1 36); do   # 최대 3분
  STATUS=$(remote "cd $REMOTE_DIR && docker inspect -f '{{.State.Health.Status}}' \$(docker compose ps -q app)" || true)
  [ "$STATUS" = healthy ] && break
  [ "$STATUS" = unhealthy ] && break
  sleep 5
done
if [ "$STATUS" != healthy ]; then
  remote "cd $REMOTE_DIR && docker compose logs --tail 60 app" || true
  die "app 이 healthy 가 되지 않았습니다 (상태: ${STATUS:-unknown}). 위 로그 확인 후 필요하면: scripts/deploy.sh --rollback <이전 sha>"
fi

HTTP=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "https://$HOST/actuator/health" || true)
[ "$HTTP" = 200 ] || die "외부 health 체크 실패 (HTTP $HTTP)"

step "기동 로그 (외부 연동 활성 여부)"
remote "cd $REMOTE_DIR && docker compose logs app 2>&1 | grep -E '활성|비활성' | tail -3 | sed 's/^.*: //'" || true

printf '\n\033[1;32m✔ 배포 완료: %s  (https://%s)\033[0m\n' "$TAG" "$HOST"
