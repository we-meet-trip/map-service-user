# map-service-user

MAP 서비스의 BFF(Backend For Frontend). Spring Boot 3.4.2 + JDK 17. 클라이언트의 모든 요청이 통과하는 단일 진입점이며 인증·일정 도메인·재탐색 정책을 단독 보유한다.

## 역할

- 인증: 자체 이메일/비밀번호(BCrypt) + Kakao OAuth (검증 후 토큰 폐기, DB 미보관)
- JWT 발급: Access 1h / Refresh 30d (SHA-256 해시 저장), RS256 (JWT_PRIVATE_KEY/JWT_PUBLIC_KEY, 미설정 시 임시 키+경고)
- 인가 시행 토글: `AUTH_ENFORCED`(기본 false=현행 전 엔드포인트 공개). true 시 recommend/schedules/trip/places/reviews 인증 필수이며, 부팅 시 JWT 키 미설정·CORS 와일드카드를 거부한다.
- 일정(Schedule) 도메인: 생성·조회·삭제, 일별 일정·장소 스냅샷·구간 경로·추천 결과 영속화
- 재탐색 정책 강제:
  - Mode 1 (동일 장소 금지 재생성): agent에 제외 장소 전달 + Redis 카운터로 일정당 3회 + KST 익일 자정까지 제한(EXPIREAT). 키 형태 `recommend:research:sched:{id}`(또는 scheduleId 부재 시 `job:{jobId}`), 초과 시 409 `RECOMMEND_001`. 카운터 장애 시 fail-open.
  - Mode 2 (부분 편집): 경로 재계산을 hub에 위임, LLM 호출 없음
  - Mode 3 (전체 수동 입력): 경로 산출을 hub에 위임, LLM 호출 없음
- 클라이언트용 Long-poll 엔드포인트: `GET /api/v1/recommend/{jobId}` — 준비 전 202 + `Retry-After: 3`, 완료 시 200 + JSON
- 추천 작업 내구성 영속화: 완료 결과를 PostgreSQL `recommend_jobs`(V005)에 write-through 하여, 휘발성 Redis draft 만료/유실 시 폴백 조회한다(응답 형식 불변).
- 리뷰 검색 프록시: `GET /api/v1/reviews?query=&display=` → hub `/v1/reviews` 위임(상류 오류 502 `review_search_upstream_error`).
- 자체 schema `user_service` 단독 쓰기, 다른 schema cross-write 금지

## 폴더 구조

```
map-service-user/
├── Dockerfile                    gradle:8.10-jdk17 builder + eclipse-temurin:17-jre runtime
├── build.gradle                  Spring Boot 3.4.2, Java 17 toolchain
├── settings.gradle, gradlew, gradle/
└── src/main/
    ├── java/map/service/user/
    │   ├── ServiceUserApplication.java   Spring Boot 진입점
    │   ├── auth/                         인증·JWT 도메인
    │   ├── schedule/                     일정 도메인
    │   ├── recommend/                    추천 결과 영속화 + 재탐색 카운터
    │   ├── common/                       공통 유틸·예외 처리
    │   └── config/                       Spring 설정 빈
    └── resources/
        ├── application.yml               datasource · JPA · Flyway · Redis · JWT
        └── db/migration/                 Flyway V001~ SQL
```

## 실행 (단독 빌드 — 통합 실행은 map-service-infra 사용 권장)

```bash
docker build -t map-service-user:dev .
docker run --rm -p 8080:8080 --env-file ../map-service-infra/.env map-service-user:dev
curl http://127.0.0.1:8080/actuator/health
```

## 의존성

- Java 17, Gradle 8.x
- Spring Boot 3.4.2
- PostgreSQL (Flyway 마이그레이션)
- Redis (세션·JWT 블랙리스트·재탐색 카운터)
- agent · hub 서비스 (내부 HTTP)

## License

MIT — see [LICENSE](LICENSE).
