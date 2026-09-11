-- Forward-only: preserve existing messages, schedules and users.
ALTER TABLE user_service.chat_messages ADD COLUMN moderation_hidden boolean NOT NULL DEFAULT false;

CREATE TABLE user_service.user_blocks (
    id bigserial PRIMARY KEY,
    blocker_id bigint NOT NULL REFERENCES user_service.users(id) ON DELETE CASCADE,
    blocked_user_id bigint NOT NULL REFERENCES user_service.users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_user_block UNIQUE (blocker_id, blocked_user_id),
    CONSTRAINT ck_user_block_not_self CHECK (blocker_id <> blocked_user_id)
);
CREATE INDEX idx_user_blocks_reverse ON user_service.user_blocks(blocked_user_id, blocker_id);

CREATE TABLE user_service.moderation_reports (
    report_id uuid PRIMARY KEY,
    reporter_id bigint REFERENCES user_service.users(id) ON DELETE SET NULL,
    client_request_id uuid NOT NULL,
    content_type varchar(24) NOT NULL,
    reason varchar(32) NOT NULL,
    status varchar(16) NOT NULL,
    description text,
    request_fingerprint varchar(64),
    message_id bigint REFERENCES user_service.chat_messages(id) ON DELETE SET NULL,
    room_id bigint REFERENCES user_service.chat_rooms(room_id) ON DELETE SET NULL,
    message_seq bigint,
    schedule_id bigint REFERENCES user_service.schedules(schedule_id) ON DELETE SET NULL,
    recommend_job_id uuid REFERENCES user_service.recommend_jobs(job_id) ON DELETE SET NULL,
    reported_user_id bigint REFERENCES user_service.users(id) ON DELETE SET NULL,
    resolution varchar(24),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_moderation_report_request UNIQUE(reporter_id, client_request_id),
    CONSTRAINT ck_moderation_type CHECK(content_type IN ('CHAT_MESSAGE','TRIP','VISION')),
    CONSTRAINT ck_moderation_status CHECK(status IN ('OPEN','IN_REVIEW','ACTIONED','DISMISSED'))
);
CREATE INDEX idx_moderation_queue ON user_service.moderation_reports(status, created_at);
CREATE INDEX idx_moderation_reporter ON user_service.moderation_reports(reporter_id, created_at);
CREATE INDEX idx_moderation_reported ON user_service.moderation_reports(reported_user_id);

CREATE TABLE user_service.moderation_actions (
    action_id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES user_service.moderation_reports(report_id),
    admin_actor varchar(96) NOT NULL,
    action varchar(24) NOT NULL,
    restriction_hours integer,
    created_at timestamptz NOT NULL
);
CREATE INDEX idx_moderation_actions_report ON user_service.moderation_actions(report_id, created_at);

CREATE TABLE user_service.chat_restrictions (
    user_id bigint PRIMARY KEY REFERENCES user_service.users(id) ON DELETE CASCADE,
    restricted_until timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
-- Target columns may become NULL on erasure. Creation-time ownership and target
-- validation live in the service; an immutable NOT NULL target check would block
-- account deletion. No raw message/image snapshot is copied to these tables.
