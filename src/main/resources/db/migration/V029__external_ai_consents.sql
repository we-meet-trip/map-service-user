-- Forward-only; explicit optional AI consent is never inferred or backfilled.
CREATE TABLE user_service.external_ai_consents (
    id varchar(64) PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES user_service.users(id) ON DELETE CASCADE,
    scope varchar(32) NOT NULL CHECK (scope IN ('trip','vision','review_summary')),
    policy_version varchar(32) NOT NULL,
    accepted boolean NOT NULL,
    include_location boolean NOT NULL CHECK (NOT include_location OR (accepted AND scope = 'vision')),
    revision bigint NOT NULL CHECK (revision > 0),
    updated_at timestamptz NOT NULL,
    UNIQUE (user_id, scope),
    CHECK (id = user_id::text || ':' || scope)
);
CREATE TABLE user_service.external_ai_job_consents (
    job_id uuid PRIMARY KEY REFERENCES user_service.recommend_jobs(job_id) ON DELETE CASCADE,
    user_id bigint NOT NULL REFERENCES user_service.users(id) ON DELETE CASCADE,
    revision bigint NOT NULL CHECK (revision > 0)
);
CREATE INDEX external_ai_job_consents_user_idx ON user_service.external_ai_job_consents(user_id);
