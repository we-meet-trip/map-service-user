-- =============================================================================
-- V009__schedules_started_at.sql — schedules 에 일정 시작 시각 컬럼 추가
--
-- 책임: 사용자가 저장한 일정을 실제로 따라가기 시작한 시점을 남긴다.
--       이전에는 시작 버튼이 화면만 바꾸고 서버에 아무 흔적도 남기지 않아,
--       다른 기기에서 이어서 열 수도 없고 만든 일정이 실제로 쓰였는지도
--       알 수 없었다.
--
-- 스키마: user_service
--
-- 컬럼(nullable — 시작하지 않은 일정과 이 마이그레이션 이전 행은 값이 없다):
-- - started_at : 처음 시작한 시각. 두 번째 시작 요청은 이 값을 덮지 않는다.
--                덮으면 "언제부터 이 일정을 따라갔는가"를 잃는다.
--
-- 되돌리기: ALTER TABLE user_service.schedules DROP COLUMN started_at;
-- =============================================================================

ALTER TABLE user_service.schedules
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ;

-- 시작한 일정만 골라 보는 조회가 목록 정렬(date_start)과 겹치지 않도록
-- 값이 있는 행만 담는 부분 인덱스를 둔다. 대부분의 행은 값이 없다.
CREATE INDEX IF NOT EXISTS idx_schedules_started_at
    ON user_service.schedules (started_at)
    WHERE started_at IS NOT NULL;
