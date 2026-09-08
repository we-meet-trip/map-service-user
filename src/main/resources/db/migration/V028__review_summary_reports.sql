-- Forward-only expansion. V026 and existing report/action data remain unchanged.
-- Deploy a User/Admin version that understands REVIEW_SUMMARY before enabling
-- its client entry point. Older Java enums cannot read rows of the new type.
ALTER TABLE user_service.moderation_reports
    ADD CONSTRAINT ck_moderation_type_v028
    CHECK(content_type IN ('CHAT_MESSAGE','TRIP','VISION','REVIEW_SUMMARY')) NOT VALID;
ALTER TABLE user_service.moderation_reports VALIDATE CONSTRAINT ck_moderation_type_v028;
ALTER TABLE user_service.moderation_reports DROP CONSTRAINT ck_moderation_type;
ALTER TABLE user_service.moderation_reports RENAME CONSTRAINT ck_moderation_type_v028 TO ck_moderation_type;
