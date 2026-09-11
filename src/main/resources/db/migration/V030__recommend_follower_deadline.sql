-- Nullable additive metadata identifies only joined jobs. Ordinary worker jobs are unaffected.
ALTER TABLE user_service.recommend_jobs
    ADD COLUMN worker_completion_hash VARCHAR(64),
    ADD COLUMN waiting_key VARCHAR(128),
    ADD COLUMN waiting_expires_at TIMESTAMPTZ;
ALTER TABLE user_service.recommend_jobs
    ADD CONSTRAINT ck_recommend_jobs_waiting_pair
    CHECK ((waiting_key IS NULL) = (waiting_expires_at IS NULL));
