-- =============================================================================
-- V007__schedules_meta.sql — schedules 에 화면 복원용 메타 3컬럼 추가
--
-- 책임: 저장된 일정을 다시 열 때 생성 당시와 같은 화면을 만들 수 있게 한다.
--       payload 에는 agent 추천 결과 원본만 들어 있어 이동수단과 활동 시간대가
--       없다. 그런데 방문 시각은 활동 시간대를 균등 분할해 만들고, 이동 카드의
--       라벨과 도로 경로 프로파일은 이동수단으로 정해진다. 두 값이 없으면
--       상세 조회가 생성 직후 화면과 다른 결과를 내놓는다.
--
-- 스키마: user_service
--
-- 컬럼(전부 nullable — 이 마이그레이션 이전에 저장된 행은 값이 없다):
-- - transport          : 이동수단. walk | bicycle | scooter | bus
-- - active_start_hour  : 하루 활동 시작 시각(0~24)
-- - active_end_hour    : 하루 활동 종료 시각(0~24)
--
-- 값이 없는 행은 조회 시 기본 활동 시간대로 대체하고 이동 라벨은 수단 없는
-- 표기를 쓴다. 데이터 보정(backfill)은 하지 않는다 — 원본에 없던 정보를
-- 추측해 채우면 화면이 사실과 달라진다.
--
-- 되돌리기: ALTER TABLE user_service.schedules
--             DROP COLUMN transport, DROP COLUMN active_start_hour,
--             DROP COLUMN active_end_hour;
-- =============================================================================

ALTER TABLE user_service.schedules
    ADD COLUMN IF NOT EXISTS transport VARCHAR(16),
    ADD COLUMN IF NOT EXISTS active_start_hour INTEGER,
    ADD COLUMN IF NOT EXISTS active_end_hour INTEGER;

-- 시각 범위는 요청 DTO 에서도 검증하지만, 다른 경로로 들어오는 값까지 막기
-- 위해 DB 에도 둔다. NULL 은 통과시킨다(값 미보유 행 허용).
ALTER TABLE user_service.schedules
    ADD CONSTRAINT chk_schedules_active_hours
        CHECK (
            (active_start_hour IS NULL OR active_start_hour BETWEEN 0 AND 24)
            AND (active_end_hour IS NULL OR active_end_hour BETWEEN 0 AND 24)
        );
