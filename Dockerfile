# challenge-server 실행 이미지.
#
# 🔴 이 이미지 안에서는 Gradle 을 돌리지 않는다 — 호스트(Mac 또는 CI)에서 먼저 JAR 를 만든다.
#    ./gradlew :app:bootJar
#    docker build --platform linux/arm64 -t challenge-server:local .
#    JAR 는 아키텍처 무관이라, x86 CI 에서 arm64(t4g) 이미지를 만들어도 COPY 만 하므로 빠르다.
FROM eclipse-temurin:17-jre

# healthcheck(curl) 용. 이미지 크기보다 "헬스체크가 실제 HTTP 로 확인한다" 쪽을 택했다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Seoul

# root 로 돌리지 않는다. 사진 저장 루트(/data/photos)는 볼륨 마운트 지점이므로 소유권을 미리 잡아 둔다.
# UID 를 10001 로 고정하는 이유: 호스트에서 바인드 마운트하는 FCM 키 파일을 이 UID 로 chown 해야 읽힌다.
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app app \
    && mkdir -p /data/photos && chown -R app:app /data
USER app

WORKDIR /app
COPY app/build/libs/app.jar app.jar

EXPOSE 8080

# 컨테이너 메모리 제한(compose mem_limit)의 75% 를 힙으로. 2GB EC2 에서 Postgres/Redis 와 나눠 쓴다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
