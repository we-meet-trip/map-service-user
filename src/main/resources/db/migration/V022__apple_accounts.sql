-- Apple subject is text; existing Kakao numeric IDs stay unchanged.
CREATE TABLE apple_accounts (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    subject VARCHAR(255) NOT NULL UNIQUE,
    refresh_token_ciphertext TEXT,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_apple_accounts_checked_at ON apple_accounts(checked_at) WHERE refresh_token_ciphertext IS NOT NULL;
