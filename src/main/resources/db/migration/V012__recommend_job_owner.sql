-- =============================================================================
-- V012__recommend_job_owner.sql — 추천 잡의 소유자 기록
--
-- 책임: 잡을 만든 사용자를 남겨, 남의 잡을 고치지 못하게 한다.
--
-- 왜 필요한가:
--   초안 수정 경로가 job_id 하나만 받고 소유자를 보지 않는다. job_id 가
--   UUID 라 짐작하기 어렵다는 것이 유일한 방어인데, 그 값은 응답 본문과
--   로그에 그대로 실려 나간다. 인증을 아직 강제하지 않는 상태로 공개
--   주소에 걸려 있으므로, 값을 알기만 하면 남의 초안을 고칠 수 있다.
--
-- 없을 수 있다:
--   토큰이 실리지 않은 요청으로 만든 잡과 이 마이그레이션 이전 잡은 비어
--   있다. 그때는 소유자를 모르는 것이므로 막지 않는다 — 모른다는 이유로
--   기존 사용자를 잠그면 인증을 켜기도 전에 기능이 멈춘다.
--   값이 있는 잡에 대해서만, 다른 사람이면 거절한다.
--
-- 외래키를 걸지 않는 이유:
--   users 와 recommend_jobs 는 수명이 다르다. 탈퇴한 사용자의 잡 기록이
--   남아도 통계와 학습 자료로는 유효하다.
--
-- 멱등성: ADD COLUMN IF NOT EXISTS.
-- =============================================================================

ALTER TABLE user_service.recommend_jobs
    ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_recommend_jobs_owner
    ON user_service.recommend_jobs(owner_user_id)
    WHERE owner_user_id IS NOT NULL;
