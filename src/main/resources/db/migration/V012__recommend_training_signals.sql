-- =============================================================================
-- V011__recommend_training_signals.sql — 학습 신호 보관 + 잡 출처 구분
--
-- 책임: 추천이 "무엇을 보고 무엇을 골랐는지" 와 "사용자가 그것을 어떻게 고쳤는지" 를
--       남긴다. 지금은 후보 목록이 agent 그래프 상태 안에서만 살다 사라지고,
--       편집은 직전 초안을 덮어써 무엇이 빠졌는지 사후에 알 수 없다.
--
-- 스키마: user_service
--
-- (1) recommend_jobs 컬럼 추가
-- - mode           : 이 잡을 만든 경로. init | research | route | refresh
-- - parent_job_id  : 재탐색일 때 원본 잡. 이것이 있어야 "이 결과를 거부하고 저것을
--                    받았다" 는 쌍이 성립한다. 논리 참조이며 외래키를 걸지 않는다
--                    (원본이 정리돼도 이후 잡이 깨지면 안 된다)
-- - source         : 결과를 실제로 만든 주체. agent | cache_hit
--                    캐시 히트 잡은 agent 가 돌지 않았는데도 done 으로 기록돼,
--                    지금까지 done 을 세면 LLM 사용량이 과대 집계됐다. 그것을
--                    문서가 아니라 스키마로 가른다
-- - schema_version : 함께 저장된 학습 신호의 계약 판
--
-- (2) recommend_training — 후보·점수·경로
-- 별도 테이블로 두는 이유: recommend_jobs 는 잡 상태를 보려고 자주 읽는데,
-- 후보 수십 개(건당 십수 KB)를 같은 행에 두면 그 조회가 매번 함께 무거워진다.
--
-- (3) recommend_edits — 편집 전후
-- 사용자가 뺀 장소가 가장 값진 신호인데 지금은 남지 않는다.
-- - UNIQUE(job_id, seq)     : 같은 잡에 동시 편집이 들어와도 순번이 겹치지 않는다
-- - UNIQUE(idempotency_key) : 같은 요청이 재시도로 두 번 들어와도 한 번만 남는다
--
-- 보존:
-- - 학습 신호와 편집 기록은 개인정보를 포함한다(어디를 가려 했는지). 무기한
--   두지 않고 created_at 기준으로 정리한다. 정리 주기는 운영에서 정한다.
--
-- 멱등성:
-- - ADD COLUMN / CREATE TABLE / INDEX 모두 IF NOT EXISTS 로 재실행 안전.
--   (Flyway 는 버전 마이그레이션을 한 번만 적용하지만, 손으로 부어 보는 경우가 있다)
--
-- 기존 행:
-- - 이 마이그레이션 이전에 만들어진 잡은 mode/source 가 NULL 이다. "미상" 으로
--   읽어야 하며 agent 실행분으로 세면 안 된다.
-- =============================================================================

ALTER TABLE user_service.recommend_jobs
    ADD COLUMN IF NOT EXISTS mode           VARCHAR(16),
    ADD COLUMN IF NOT EXISTS parent_job_id  UUID,
    ADD COLUMN IF NOT EXISTS source         VARCHAR(16),
    ADD COLUMN IF NOT EXISTS schema_version INTEGER;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_recommend_jobs_mode'
    ) THEN
        ALTER TABLE user_service.recommend_jobs
            ADD CONSTRAINT chk_recommend_jobs_mode
            CHECK (mode IS NULL OR mode IN ('init', 'research', 'route', 'refresh'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_recommend_jobs_source'
    ) THEN
        ALTER TABLE user_service.recommend_jobs
            ADD CONSTRAINT chk_recommend_jobs_source
            CHECK (source IS NULL OR source IN ('agent', 'cache_hit'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_recommend_jobs_parent
    ON user_service.recommend_jobs(parent_job_id)
    WHERE parent_job_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_recommend_jobs_source
    ON user_service.recommend_jobs(source);

CREATE TABLE IF NOT EXISTS user_service.recommend_training (
    job_id         UUID PRIMARY KEY,
    schema_version INTEGER NOT NULL,
    payload        JSONB   NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 경로를 모르면 후보 목록의 의미가 정해지지 않는다. 최소한 이 둘은 있어야
    -- 나중에 읽을 수 있다.
    CONSTRAINT chk_recommend_training_shape
        CHECK (payload ? 'path' AND payload ? 'schema_version')
);

CREATE INDEX IF NOT EXISTS idx_recommend_training_created_at
    ON user_service.recommend_training(created_at);

CREATE TABLE IF NOT EXISTS user_service.recommend_edits (
    id              BIGSERIAL   PRIMARY KEY,
    job_id          UUID        NOT NULL,
    seq             INTEGER     NOT NULL,
    actor_user_id   BIGINT,
    idempotency_key VARCHAR(64),
    before_payload  JSONB       NOT NULL,
    after_payload   JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_recommend_edits_job_seq UNIQUE (job_id, seq)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_recommend_edits_idem
    ON user_service.recommend_edits(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_recommend_edits_created_at
    ON user_service.recommend_edits(created_at);
