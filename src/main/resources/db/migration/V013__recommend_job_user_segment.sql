-- V013__recommend_job_user_segment.sql — 잡을 만든 사람의 성향 스냅샷
--
-- 왜 스냅샷인가:
--   학습의 입력에는 "누가 물었는가" 가 필요한데, 그것을 users 조인으로 그때그때
--   읽으면 사람이 나중에 프로필을 고쳤을 때 과거 잡의 입력까지 바뀐다. 그러면
--   같은 기록을 두 번 읽을 때 다른 값이 나와, 학습과 재현이 어긋난다.
--   물어본 그 시점의 값을 잡에 붙여 둔다.
--
-- 왜 원문이 아니라 묶음인가:
--   생년월일과 성별 원문을 학습 평면에 한 벌 더 두면, 그 평면이 새어 나갔을 때
--   원문이 함께 나간다. 나이는 10년 단위로 뭉개고 성별은 코드로 바꿔, 학습에
--   필요한 만큼만 옮긴다. 원문은 users 에만 둔다.
--   (같은 행에 owner_user_id 가 있으므로 이것이 익명화는 아니다. 목적은
--    "원문을 복제하지 않는 것" 이고, 밖으로 내보낼 때의 익명화는 내보내기
--    쪽에서 식별자를 해시로 바꾸는 것으로 따로 한다.)
--
-- 담기는 모양: {"age_band":"20s"|"unknown", "gender":"m"|"f"|null,
--               "themes":[...], "theme_merged":true|false}
--   theme_merged 는 그 요청의 테마에 저장된 취향이 섞였는지다. 섞인 요청은
--   입력(요청 테마)에 이 사람의 성향이 이미 들어가 있어, 성향을 따로 쓰는
--   학습에서 같은 정보를 두 번 세게 된다. 갈라 보려면 표시가 필요하다.

ALTER TABLE user_service.recommend_jobs
    ADD COLUMN IF NOT EXISTS user_segment JSONB;

COMMENT ON COLUMN user_service.recommend_jobs.user_segment IS
    '잡 생성 시점 요청자 성향 스냅샷(연령대·성별·테마·취향병합여부). 원문 미포함.';
