-- Forward-only. Existing accounts must explicitly accept; no backfilled consent.
CREATE TABLE user_service.service_policy_acceptances (
    user_id bigint PRIMARY KEY REFERENCES user_service.users(id) ON DELETE CASCADE,
    terms_version varchar(32) NOT NULL,
    privacy_version varchar(32) NOT NULL,
    is_18_or_older boolean NOT NULL CHECK (is_18_or_older),
    accepted_at timestamptz NOT NULL
);
