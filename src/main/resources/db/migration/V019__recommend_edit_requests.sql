-- Operational idempotency receipts, separate from held training capture.
-- The application encrypts response_payload and expires receipts using the draft TTL.
CREATE TABLE user_service.recommend_edit_requests (
    id VARCHAR(64) PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES user_service.recommend_jobs(job_id) ON DELETE CASCADE,
    request_hash VARCHAR(64) NOT NULL,
    response_payload JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_recommend_edit_requests_expires
    ON user_service.recommend_edit_requests(expires_at);
