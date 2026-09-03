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
- 날씨 변화 감지 → 1클릭 재추천:
  - 일정을 저장할 때 그 시점 예보를 `schedules.weather_baseline`(V011)에 기준선으로 굳힌다. 지역(광역시도·시군구)은 추천 요청에만 실려 있어 `recommend_jobs`(V011)에 먼저 남기고 저장 시 일정으로 옮긴다.
  - `ScheduleWeatherWatcher` 가 주기적으로(기본 30분, `WEATHER_WATCH_INTERVAL_MS`) 끝나지 않은 일정의 예보를 다시 받아 기준선과 견준다. 강수확률 50% 경계를 넘나들거나 하늘 상태가 비·눈으로 바뀌면 `schedules.weather_alert` 에 알림을 건다(`rain_appeared` / `rain_cleared`). 감시 자체는 `WEATHER_WATCH_ENABLED=false` 로 끌 수 있다.
  - 걸린 알림은 일정 목록·상세 응답의 `weather_alert` 로 나간다(없으면 필드 자체가 없음). **종료일이 지난 일정은 감시도, 알림 노출도, 재추천도 하지 않는다** — 다녀온 여행에 배너가 남으면 사용자가 할 일이 없다.
  - `POST /api/v1/schedules/{id}/weather-alert/dismiss` — "알고 있고 그대로 갈래". 알림을 지우고 기준선을 지금 예보로 옮긴다(204). 기준선을 옮기지 않으면 다음 순회에서 같은 변화가 다시 잡힌다. 이후 예보가 **또** 달라지면 새 기준선 대비로 다시 알린다. 지금 예보를 못 받으면 기준선은 그대로 두고 알림만 지운다(기준선을 비우면 감시 대상에서 영영 빠진다).
  - `POST /api/v1/trip/replan` (본문 `{schedule_id}`) — **앱이 쓰는 동기 경로.** 저장된 지역·기간·이동수단으로 다시 짜서 완성된 `TripGenerateResponse`(stops·weather_forecast 포함)를 200 으로 돌려준다. generate/route 와 응답이 같아 결과 화면을 그대로 재사용한다. 성공한 뒤에만 일정의 기준선을 갱신한다 — 실패한 재추천으로 알림을 지우면 사용자가 바뀐 날씨를 모른 채 옛 코스를 들고 간다.
  - `POST /api/v1/schedules/{id}/replan` — 비동기 경로(job_id 반환, 폴링 필요). 저장된 지역·기간·이동수단으로 새 추천 작업을 띄우고 202 + `job_id`. 결과는 기존 `GET /api/v1/recommend/{jobId}` 로 받는다. 일반 추천 경로라 **재탐색 1일 3회 한도를 깎지 않는다**(날씨 악화는 사용자 변심이 아니라 외부 변수). 지역을 모르는 옛 일정과 이미 지나간 일정은 409 `replan_unavailable`.
    - **재사용 캐시를 타지 않는다.** 캐시 키(지역·기간·테마·이동수단·예산·시간대)에 날씨가 없어 조건이 그대로인 재추천은 반드시 캐시에 맞고, 맞으면 agent 가 아예 돌지 않아 `fetch_weather` 도 돌지 않는다 — 비 오기 전 코스가 그대로 돌아온다. 결과를 캐시에 등록하지도 않는다(반대 방향 오염 방지).
    - 날씨는 요청에 싣지 않는다. agent 의 `fetch_weather` 노드가 지역·기간으로 hub 에 직접 물으므로 **추천이 실제로 도는 시점**의 예보가 반영된다.
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
