-- V015__schedule_tombstone.sql — 지운 일정을 흔적으로 남긴다
--
-- 왜 흔적을 남기나:
--   "저장했다가 지웠다" 는 사용자가 남기는 가장 뚜렷한 부정 신호다. 지금은
--   행을 통째로 지워서 그 판단이 아무 데도 남지 않는다. 저장만 정답으로 쓰면
--   "받아들였다" 만 배우고 "물렀다" 는 못 배운다.
--
-- 지운 것이 계속 남는 문제:
--   흔적으로 바꾸면 사용자가 지운 것이 저장소에 계속 남는다. 그래서 기한을
--   함께 둔다 — 90일이 지나면 진짜로 지운다. 그때 연결된 채팅방·도착 기록도
--   외래키를 타고 함께 정리된다(지금은 일정을 지울 때만 그 정리가 일어난다).
--
-- 읽는 쪽:
--   엔티티에 걸린 조건으로 흔적은 조회에서 통째로 빠진다. 학습용 내보내기만
--   네이티브 SQL 로 직접 읽어 "지웠다" 를 신호로 쓴다.

ALTER TABLE user_service.schedules
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- 정리 스위퍼가 기한 지난 흔적만 훑는다. 살아 있는 행(대다수)은 색인에서 뺀다.
CREATE INDEX IF NOT EXISTS idx_schedules_deleted_at
    ON user_service.schedules(deleted_at)
    WHERE deleted_at IS NOT NULL;

COMMENT ON COLUMN user_service.schedules.deleted_at IS
    '사용자가 지운 시각. 있으면 조회에서 빠지고, 기한이 지나면 실제로 삭제된다.';
