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

동시 요청은 Redis 실행 세대별 불변 완료 스냅샷을 사용한다. consumer가 받은
워커 원문을 job AAD로 암호화해 짧게 보존하고, 공유 가능한 실행에 한해서
producer 식별자와 본문 전체를 세대 token AAD로 다시 암호화한다. follower는
이를 자기 job_id로 복사한다. PG 최초 worker terminal과 같은 트랜잭션에
job-bound SHA256 원문 지문을 기록하고, 그 지문과 같은 event만 스냅샷을
발행할 수 있다. 첫 event의 Redis 저장이 실패하면 ACK하지 않아 PEL의 동일
원문 재배달로 복구한다. 이후 다른 성공·실패 event는 원본이 될 수 없다.
소유자가 편집할 수 있는 PG 결과는 원본으로
조회하지 않는다. 첫 스냅샷 이후 재배달은 이를 덮지 않으며 취소 표식이 우선한다.
등록보다 빨리 도착한 완료도 같은 불변 원문으로만 연결을 복구한다.

워커 스냅샷 TTL은 `redis.cache-link-ttl-seconds`(기본 3600초), 세대 완료와
대기 TTL은 `redis.inflight-ttl-seconds`(기본 600초)이다. V030은 follower 행에
대기 세대와 기한을 최초 생성과 같은 트랜잭션으로 기록한다. Redis wait 키가
없어도 이 기록으로 조회하며, 완료 스냅샷이 없거나 Redis 장애인 채 기한이
지나면 다음 조회에서 `generation_failed`, `retryable: false`로 영속 종결한다.
성공·만료·중복 조회가 경합해도 PG 잠금에서 채택된 첫 terminal만 반환한다.
일반 producer/route에는 이 기한을 적용하지 않는다. 종결 시 대기 메타데이터를
지우며, 계정 탈퇴는 커밋 뒤 스냅샷을 취소 표식으로 교체한다. 캐시 장애 시에도
PG 취소 표식이 접근을 막고 TTL이 잔여 본문의 최종 보존 상한이다.

V030 이전에 Redis 표식까지 사라진 in_progress 행은 producer/follower를
안전하게 구분할 수 없다. 기존 완료 행에 지문이 없을 때도 뒤늦은 event로
원본 지문을 임의 생성하지 않는다. 배포 전에 오래된 in_progress 합계 0의 비식별 증거를
확인해야 한다. 0이 아니면 배포를 보류하고 해당 실행의 worker 상태와 취소
기록을 대조한 제한된 복구 절차를 별도로 검토한다. 무표식 행 전체를 임의로
실패시키지 않는다. 기존 bare producer ID 완료 표식은 안전한 일반 실패로
처리하며 해당 사용자의 가변 PG 결과를 읽는 호환 경로는 없다.

실패를 장기 성공 cache에 넣지 않는다. 접수 실패·취소는 무해한 일반 실패로
전달하고 다른 사용자의 취소 이유·식별자를 응답하지 않는다.
Redis stream의 재배달은 저장 실패를 복구하는 것이며 추천 재생성이 아니다.
