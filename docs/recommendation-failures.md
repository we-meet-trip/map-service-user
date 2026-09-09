# 추천 실패 응답 계약

Agent 완료 payload와 User 추천 결과는 기존 필드에 `code`, `retryable`을 추가한다.
비동기 조회는 완료 실패에도 HTTP 200과 `status: "failed"`를 유지한다.
Trip facade는 기존 `error: "trip_generation_failed"`, `message`를 유지한다.
두 경로 모두 알려진 코드와 고정 안내만 반환하며 과거에 저장된 원문 오류는 노출하지 않는다.

| code | facade HTTP | retryable |
|---|---|---|
| no_matching_places | 422 | false |
| selection_invalid | 502 | false |
| invalid_request | 422 | false |
| upstream_unavailable | 503 | 명확한 일시 장애일 때만 true |
| quota_exceeded | 429 | false |
| generation_timeout | 504 | worker terminal timeout일 때 true |
| generation_failed | 502 | false |

`no_matching_places`는 현재 검색·제외 조건에서 사용할 후보가 없다는 뜻이다.
후보가 존재하지만 모델 선택 인덱스·일차·좌표 검증을 통과하지 못한 경우는
`selection_invalid`이며 지역 전체 후보풀이 고갈되었다는 의미가 아니다.
Hub provider 장애로 결과가 비는 경우는 `upstream_unavailable`로 구분한다.

별도 facade 상태:

- `recommendation_pending`: facade 대기 시간만 초과한 HTTP 504. worker는 실행 중일 수 있어 `retryable: false`이다. 기존 `error: "trip_generation_timeout"`을 유지한다.
- `timeline_changed`: 로컬 일정 시간축 검증 실패 HTTP 502, `retryable: false`. 기존의 고정 시간축 안내 문구를 유지한다.

`retryable`은 동일 입력으로 나중에 다시 요청하도록 권장할 수 있다는 뜻이며
자동 재실행이나 성공 보장이 아니다. 서버·클라이언트는 이 필드를 이유로
지역·테마·제외 조건을 변경하거나 새 추천 POST를 자동 실행하지 않는다.
코드가 없거나 알려지지 않은 과거 실패는 `generation_failed`, false로 처리한다.

동시 요청은 Redis 실행 세대별 완료 참조를 사용한다. follower는 자신이 기다리던
세대의 PG 완료본을 자기 job_id로 복사하며 성공·실패 상태를 보존한다.
실패를 장기 성공 cache에 넣지 않는다. 접수 실패·취소는 무해한 일반 실패로
전달하고 다른 사용자의 취소 이유·식별자를 응답하지 않는다.
Redis stream의 재배달은 저장 실패를 복구하는 것이며 추천 재생성이 아니다.
