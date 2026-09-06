-- Protect new writes from committing a schedule for an account withdrawn concurrently.
-- NOT VALID leaves legacy orphan rows untouched; review those rows before a separate
-- VALIDATE CONSTRAINT migration. It still enforces all new/changed user references.
ALTER TABLE user_service.schedules ADD CONSTRAINT fk_schedule_user
    FOREIGN KEY (user_id) REFERENCES user_service.users(id) ON DELETE CASCADE NOT VALID;
