# 배포 (EC2 한 대 + docker compose)

```
[앱] --HTTPS--> api.example.com (가비아 A 레코드 → Elastic IP)
                 └ EC2: nginx(443) → app(8080) → postgres / redis
```

## 파일

| 경로 | 내용 |
|---|---|
| `../Dockerfile` | 실행 이미지. 호스트에서 만든 `app/build/libs/app.jar` 를 복사만 한다 |
| `docker-compose.yml` | app / postgres / redis + (`https` 프로필) nginx / certbot |
| `nginx/templates/default.conf.template` | HTTPS 종료, 80→443 리다이렉트, 업로드 6MB, WebSocket |
| `.env.example` | 환경변수 목록. 복사해서 `.env` 로 쓴다 (커밋 금지) |
| `secrets/fcm.json` | FCM 서비스 계정 키 (커밋 금지, 직접 둔다) |

## 1. 로컬 검증

```bash
./gradlew :app:bootJar
docker build -t challenge-server:local .

cd deploy
cp .env.example .env   # DB_PASSWORD, JWT_SECRET 채우기. COMPOSE_PROFILES / APP_IMAGE 는 비워 둔다
docker compose up -d
docker compose logs -f app
curl localhost:8080/actuator/health   # {"status":"UP"}
```

확인할 것: Flyway 마이그레이션 성공, 로그인, 사진 업로드 → `docker compose restart app` 후에도 사진 조회.

## 2. 이미지 push (Mac → ECR)

```bash
REGISTRY=<계정ID>.dkr.ecr.ap-northeast-2.amazonaws.com
./gradlew :app:bootJar
docker build --platform linux/arm64 -t $REGISTRY/challenge-server:latest .
aws ecr get-login-password | docker login --username AWS --password-stdin $REGISTRY
docker push $REGISTRY/challenge-server:latest
```

## 3. EC2 최초 설정 (한 번만)

```bash
# /srv/challenge 에 deploy/ 내용(docker-compose.yml, nginx/)을 올린다
cd /srv/challenge
cp .env.example .env && chmod 600 .env   # COMPOSE_PROFILES=https, APP_IMAGE, DOMAIN 등 채우기

# FCM 키 — 컨테이너 사용자(UID 10001)만 읽게 한다
mkdir -p secrets && mv ~/fcm.json secrets/fcm.json
sudo chown 10001:10001 secrets/fcm.json && sudo chmod 400 secrets/fcm.json

# 인증서 최초 발급 — nginx 가 뜨기 전에 standalone 으로 (80 포트가 비어 있어야 한다)
set -a; . ./.env; set +a
docker compose run --rm -p 80:80 --entrypoint certbot certbot \
  certonly --standalone -d "$DOMAIN" --email "$CERTBOT_EMAIL" --agree-tos --no-eff-email

aws ecr get-login-password | docker login --username AWS --password-stdin <계정ID>.dkr.ecr.ap-northeast-2.amazonaws.com
docker compose up -d
```

이후 갱신은 certbot 컨테이너(12시간마다 `renew`)와 nginx(6시간마다 reload)가 알아서 한다.

## 4. 재배포

```bash
cd /srv/challenge
docker compose pull app && docker compose up -d app
```

## 배포 후 확인

- `curl https://$DOMAIN/actuator/health` → `{"status":"UP"}`
- `docker compose logs app | grep -E "활성|비활성"` — FCM / Gemini / Kakao unlink 가 **활성**인지.
  키가 비어 있어도 서버는 정상 기동하므로 이 로그가 유일한 신호다.
- 운영 `.env` 에 `CHALLENGE_DEV_TEST_LOGIN_ENABLED` 가 **없어야** 한다 (테스트 로그인 = 인증 우회).
