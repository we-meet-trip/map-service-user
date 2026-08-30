-- =============================================================================
-- V011__weather_replan.sql — 날씨 변화 감지·재추천에 필요한 컬럼 추가
--
-- 책임: 저장된 일정이 "저장하던 날의 예보"를 기억하게 해서, 나중 예보와 견줘
--       코스를 다시 짤 만한 변화인지 판단할 수 있게 한다.
--
-- 왜 지역을 두 곳에 두는가:
--   hub 의 날씨는 좌표가 아니라 지역명(광역시도+시군구)으로 묻는다. 그런데 그
--   지역명은 추천 요청에만 실려 있고 추천 결과 payload 에는 남지 않는다. 그래서
--   추천 작업(recommend_jobs)에 먼저 새겨 두고, 그 작업으로 일정을 저장할 때
--   일정(schedules)으로 옮긴다. 일정에 직접 두는 이유는 감시 배치가 작업 행을
--   거치지 않고 일정만 훑을 수 있게 하기 위함이다.
--
-- 스키마: user_service
--
-- recommend_jobs 추가 컬럼(전부 nullable — 이전에 만들어진 작업은 값이 없다):
-- - province / city : 요청에 실려 온 광역시도·시군구
--
-- schedules 추가 컬럼(전부 nullable — 이전에 저장된 일정은 값이 없다):
-- - province / city      : 추천 당시 지역. 없으면 날씨 감시 대상에서 빠진다
-- - weather_baseline     : 저장 시점의 날짜별 예보(JSONB 배열).
--                          [{"date":"2026-09-01","pop":20,"sky":"sunny"}, ...]
-- - weather_alert        : 지금 걸려 있는 변화 알림(JSONB). 없으면 NULL.
--                          {"kind":"rain_appeared","date":...,"pop_before":...}
-- - weather_checked_at   : 마지막으로 예보를 다시 받아 견준 시각
--
-- 값 보정(backfill)은 하지 않는다. 지난 일정의 지역을 좌표로 되짚어 채우면
-- 엉뚱한 동네 날씨로 "비 온다"고 알리게 된다 — 모르는 건 모르는 채로 둔다.
--
-- 인덱스:
-- - idx_schedules_weather_watch : 감시 배치가 훑는 조건(끝나지 않았고 지역·기준선
--   보유)에 맞춘 부분 인덱스. 일정이 쌓여도 배치가 전수 조회를 하지 않게 한다.
--
-- 멱등성: ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS 로 재실행 안전.
--
-- 되돌리기:
--   ALTER TABLE user_service.recommend_jobs
--     DROP COLUMN province, DROP COLUMN city;
--   DROP INDEX IF EXISTS user_service.idx_schedules_weather_watch;
--   ALTER TABLE user_service.schedules
--     DROP COLUMN province, DROP COLUMN city,
--     DROP COLUMN weather_baseline, DROP COLUMN weather_alert,
--     DROP COLUMN weather_checked_at;
-- =============================================================================

ALTER TABLE user_service.recommend_jobs
    ADD COLUMN IF NOT EXISTS province VARCHAR(20),
    ADD COLUMN IF NOT EXISTS city     VARCHAR(20);

ALTER TABLE user_service.schedules
    ADD COLUMN IF NOT EXISTS province           VARCHAR(20),
    ADD COLUMN IF NOT EXISTS city               VARCHAR(20),
    ADD COLUMN IF NOT EXISTS weather_baseline   JSONB,
    ADD COLUMN IF NOT EXISTS weather_alert      JSONB,
    ADD COLUMN IF NOT EXISTS weather_checked_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_schedules_weather_watch
    ON user_service.schedules (date_end)
    WHERE province IS NOT NULL
      AND city IS NOT NULL
      AND weather_baseline IS NOT NULL;
