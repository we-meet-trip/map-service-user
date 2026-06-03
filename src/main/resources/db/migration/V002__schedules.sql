-- =============================================================================
-- V002__schedules.sql — schedules 테이블 + 2개 인덱스 생성
--
-- 책임: 사용자가 확정한 추천 결과를 일정(schedule)으로 저장하기 위한 테이블 정의.
--
-- 스키마: user_service
--
-- 컬럼:
-- - schedule_id : PK, BIGSERIAL 시퀀스로 자동 증가
-- - user_id     : 일정의 소유자 사용자 식별자 (nullable)
-- - job_id      : 본 일정의 근원이 된 추천 작업 UUID (nullable)
-- - title       : 일정 제목 (자유 텍스트)
-- - date_start  : 일정 시작일 (NOT NULL)
-- - date_end    : 일정 종료일 (NOT NULL)
-- - payload     : 추천 결과 본문 전체 JSON (JSONB, NOT NULL)
-- - created_at  : 레코드 생성 시각 (NOT NULL, 기본값 now())
--
-- 인덱스:
-- - idx_schedules_user_id : 사용자 단위 조회 최적화
-- - idx_schedules_job_id  : job_id 역참조 조회 최적화
--
-- 멱등성:
-- - CREATE TABLE / INDEX 모두 IF NOT EXISTS 로 재실행 안전.
-- =============================================================================

CREATE TABLE IF NOT EXISTS user_service.schedules (
    schedule_id   BIGSERIAL PRIMARY KEY,
    user_id       BIGINT,
    job_id        UUID,
    title         TEXT,
    date_start    DATE NOT NULL,
    date_end      DATE NOT NULL,
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_schedules_user_id ON user_service.schedules(user_id);
CREATE INDEX IF NOT EXISTS idx_schedules_job_id  ON user_service.schedules(job_id);
