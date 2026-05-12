# map-service-user — Docker 빌드 스켈레톤
#
# 본 파일은 의도적으로 비어 있다. 실제 코드 작성 단계에서 아래 항목을 채운다.
#
# 작성 예정:
#   1. builder 스테이지   — gradle:8.x-jdk21, gradle bootJar (테스트 스킵 옵션 검토)
#   2. runtime 스테이지   — eclipse-temurin:21-jre 슬림
#   3. 비-root 사용자     — groupadd/useradd app
#   4. COPY --from=builder /workspace/build/libs/*.jar /app/app.jar
#   5. EXPOSE 8080
#   6. HEALTHCHECK        — curl /actuator/health
#   7. ENTRYPOINT         — java -jar /app/app.jar
