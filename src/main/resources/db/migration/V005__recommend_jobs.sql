-- =============================================================================
-- V005__recommend_jobs.sql — recommend_jobs 테이블 + 2개 인덱스 생성
--
-- 책임: 추천 작업(job)의 상태/결과를 PostgreSQL 에 write-through 로 영속화하여,
--       Redis draft(휘발성)가 만료/유실된 뒤에도 완료 결과를 폴백 조회할 수 있게 한다.
--       Redis 는 여전히 1차 저장소이며 본 테이블은 내구성 폴백 용도이다.
--
-- 스키마: user_service
--
-- 컬럼:
-- - job_id         : PK, agent 가 발급한 작업 UUID
-- - schedule_id    : 연관 일정 식별자 (nullable, 최대 64자)
-- - status         : 작업 상태. in_progress | done | failed (기본 in_progress)
-- - result_payload : 완료 결과 본문 JSON (JSONB, nullable — 진행 중에는 비어 있음)
-- - error          : 실패 사유 텍스트 (nullable)
-- - created_at     : 레코드 생성 시각 (NOT NULL, 기본값 now())
-- - finished_at    : 완료(done/failed) 기록 시각 (nullable)
--
-- 제약:
-- - chk_recommend_jobs_status : status 값 도메인 강제(in_progress/done/failed).
--
-- 인덱스:
-- - idx_recommend_jobs_status     : 상태 단위 조회/집계 최적화
-- - idx_recommend_jobs_created_at : 생성 시각 기준 정리/조회 최적화
--
-- 멱등성:
-- - CREATE TABLE / INDEX 모두 IF NOT EXISTS 로 재실행 안전.
-- =============================================================================

CREATE TABLE IF NOT EXISTS user_service.recommend_jobs (
    job_id         UUID PRIMARY KEY,
    schedule_id    VARCHAR(64),
    status         VARCHAR(16) NOT NULL DEFAULT 'in_progress',
    result_payload JSONB,
    error          TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    CONSTRAINT chk_recommend_jobs_status CHECK (status IN ('in_progress', 'done', 'failed'))
);

CREATE INDEX IF NOT EXISTS idx_recommend_jobs_status     ON user_service.recommend_jobs(status);
CREATE INDEX IF NOT EXISTS idx_recommend_jobs_created_at ON user_service.recommend_jobs(created_at);
