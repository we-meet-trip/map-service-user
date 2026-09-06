-- Nullable for existing tokens; the next rotation adopts the original token hash as its family.
ALTER TABLE user_service.refresh_tokens ADD COLUMN session_id VARCHAR(64);
CREATE INDEX idx_refresh_session_active ON user_service.refresh_tokens(session_id) WHERE revoked_at IS NULL;
