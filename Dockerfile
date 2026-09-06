# =============================================================================
# Dockerfile — user-BFF (service-user) 멀티 스테이지 빌드
#
# 책임:
# - builder 스테이지에서 bootJar 를 빌드
# - runtime 스테이지에서 비루트 사용자로 부트 jar 를 실행
# - 액추에이터 health 엔드포인트를 헬스체크로 사용
#
# 빌드:
#   docker build -t map-service-user .
# 실행:
#   docker run -p 8080:8080 map-service-user
# =============================================================================

# syntax=docker/dockerfile:1.7
# map-service-user — user-BFF 컨테이너 이미지

# ---- builder : 소스 → bootJar ----
# - gradle 이미지 위에서 워크스페이스를 복사하고 bootJar 만 빌드한다.
# - 테스트는 여기서 건너뛴다. 만드는 시간을 줄이려는 것이고, 대신 이미지를
#   만드는 실행이 만들기 전에 검사를 돌린다. 그 자리가 없으면 아무 데서도
#   안 도는 상태가 되므로 둘은 한 벌이다.
# - --no-daemon : 데몬 잔여 프로세스 회피.
# 래퍼가 정한 판과 같은 것을 쓴다. 갈라지면 손에서 통과한 것이 이미지 안에서
# 다른 도구로 만들어지고, 그 차이는 만든 결과에서만 드러난다.
FROM gradle:8.12.1-jdk17 AS builder
WORKDIR /workspace
COPY --chown=gradle:gradle . .
RUN gradle clean bootJar -x test --no-daemon

# ---- runtime : 실행 이미지 ----
# - 슬림한 JRE 베이스 이미지 사용.
# - app 그룹/사용자(uid 10001)를 만들어 비루트로 실행한다 (보안).
# - builder 산출물(/workspace/build/libs/*.jar) 을 /app/app.jar 로 복사.
# - EXPOSE 8080 : application.yml 의 server.port 와 일치.
# - HEALTHCHECK : 30s 간격, 5s 타임아웃, 기동 후 40s 대기, 3회 재시도.
#                 /actuator/health 응답에서 "status":"UP" 문자열을 확인한다.
# - ENTRYPOINT  : java -jar 로 부트 애플리케이션 실행.
FROM eclipse-temurin:17-jre AS runtime
# The base image also ships Pebble, an unused Go service manager. This image
# starts Java directly and needs no second supervisor or its vulnerable runtime.
RUN rm -f /usr/bin/pebble \
    && groupadd -r app && useradd -r -g app -u 10001 app
WORKDIR /app
COPY --from=builder /workspace/build/libs/*.jar /app/app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
