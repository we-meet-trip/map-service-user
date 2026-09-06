-- Detach a deleted account's room before removing schedules, preserving other members' messages.
ALTER TABLE user_service.chat_rooms ALTER COLUMN schedule_id DROP NOT NULL;
ALTER TABLE user_service.chat_rooms ALTER COLUMN owner_id DROP NOT NULL;
-- V021 requires reviewed participant state; these prevent a concurrent join from recreating
-- participant identifiers after account deletion.
ALTER TABLE user_service.chat_participants ADD CONSTRAINT fk_chat_participant_user
    FOREIGN KEY (user_id) REFERENCES user_service.users(id) ON DELETE CASCADE;
ALTER TABLE user_service.chat_membership_intervals ADD CONSTRAINT fk_chat_interval_user
    FOREIGN KEY (user_id) REFERENCES user_service.users(id) ON DELETE CASCADE;
