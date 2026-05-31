-- ==========================================================
-- V1: user_service schema 초기화
-- SW Architecture v5.0 §9.2 기준
-- ==========================================================

CREATE SCHEMA IF NOT EXISTS user_service;
SET search_path TO user_service;

-- ──────────────────────────────────────────────────────────
-- users
-- auth_provider: EMAIL | KAKAO | APPLE  (JPA EnumType.STRING 저장값 기준)
-- password_hash: BCrypt 60자, Kakao 단독 사용자는 NULL
-- ──────────────────────────────────────────────────────────
CREATE TABLE users (
    id                BIGSERIAL    PRIMARY KEY,
    email             VARCHAR(320) UNIQUE,
    nickname          VARCHAR(50)  NOT NULL,
    profile_image_url TEXT,
    password_hash     VARCHAR(60),
    auth_provider     VARCHAR(16)  NOT NULL DEFAULT 'EMAIL',
    is_email_verified BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email         ON users (email) WHERE email IS NOT NULL;
CREATE INDEX idx_users_auth_provider ON users (auth_provider);

-- ──────────────────────────────────────────────────────────
-- oauth_accounts
-- 카카오 OAuth 계정 연동 정보. 토큰은 미보관.
-- ──────────────────────────────────────────────────────────
CREATE TABLE oauth_accounts (
    id               BIGSERIAL   PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider         VARCHAR(16) NOT NULL,
    provider_user_id BIGINT      NOT NULL,
    scope            VARCHAR(500),
    connected_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_oauth_provider UNIQUE (provider, provider_user_id)
);

CREATE INDEX idx_oauth_user_id ON oauth_accounts (user_id);

-- ──────────────────────────────────────────────────────────
-- user_devices (PoC 스텁 — FCM/APNs 미도입)
-- ──────────────────────────────────────────────────────────
CREATE TABLE user_devices (
    id           BIGSERIAL   PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_token TEXT,
    device_type  VARCHAR(16) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_devices_user_id ON user_devices (user_id);

-- ──────────────────────────────────────────────────────────
-- refresh_tokens
-- revoked_at: NULL = 유효, NOT NULL = 폐기 (폐기 시각 기록)
-- ──────────────────────────────────────────────────────────
CREATE TABLE refresh_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64)  NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_user_id    ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_token_hash ON refresh_tokens (token_hash);

-- ──────────────────────────────────────────────────────────
-- 이하 스텁 (M2 이후 상세 스키마 추가 예정)
-- ──────────────────────────────────────────────────────────
CREATE TABLE friends (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    friend_id  BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_friends UNIQUE (user_id, friend_id)
);

CREATE TABLE share_sessions (
    id         BIGSERIAL   PRIMARY KEY,
    owner_id   BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title      VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE share_members (
    id               BIGSERIAL   PRIMARY KEY,
    share_session_id BIGINT      NOT NULL REFERENCES share_sessions(id) ON DELETE CASCADE,
    user_id          BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE trips (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title      VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE trip_recommendations (
    id         BIGSERIAL   PRIMARY KEY,
    trip_id    BIGINT      NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    stage      SMALLINT    NOT NULL DEFAULT 1,
    payload    JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE trip_segments (
    id              BIGSERIAL   PRIMARY KEY,
    trip_id         BIGINT      NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    from_content_id VARCHAR(100),
    to_content_id   VARCHAR(100),
    geom            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE media_objects (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bucket     VARCHAR(100),
    object_key TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
