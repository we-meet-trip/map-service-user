-- V014__backfill_cache_hit_lineage.sql — 이미 쌓인 캐시 사본에 계보를 달아 준다
--
-- 왜 필요한가:
--   캐시로 답한 잡은 그 결과를 처음 만든 잡을 가리키지 않고 있었다. 후보와
--   선택 근거는 원본 잡에만 붙어 있으므로, 끈이 없으면 사용자가 실제로 저장한
--   일정이 어떤 후보에서 나왔는지 알 수 없다. 코드는 고쳤지만 그것은 앞으로
--   만들어질 잡에만 듣는다 — 이미 쌓인 것은 여기서 한 번 이어 준다.
--
-- 어떻게 잇는가:
--   사본은 원본의 결과를 그대로 복사하고 job_id 만 자기 것으로 바꾼다. 그래서
--   job_id 를 뺀 나머지가 똑같은 잡이 원본이다. 이 방법은 지금 한 번만 유효하다
--   — 초안을 고치면 완료 기록도 함께 바뀌어(고친 결과로 덮인다) 그 뒤로는
--   사본과 원본의 나머지가 더 이상 같지 않기 때문이다.
--
-- 무엇을 건드리지 않는가:
--   * 이미 계보가 있는 행 (parent_job_id IS NOT NULL)
--   * 원본 후보가 둘 이상인 행 — 어느 쪽인지 못 가리므로 비운 채 둔다.
--     잘못 이으면 남의 후보로 학습하게 되어, 비어 있는 것보다 나쁘다.
--   * 결과 기록이 없는 행 (비교할 것이 없다)
--
-- 실측(2026-08-23): 대상 52건, 전부 유일 매칭, 다중 매칭 0.

UPDATE user_service.recommend_jobs c
SET parent_job_id = m.origin_id
FROM (
    -- 아래 HAVING 이 후보를 하나로 제한하므로 첫 원소가 곧 그 하나다.
    -- (UUID 에는 min/max 가 없어 모아서 집는다.)
    SELECT copy_id, (array_agg(origin_id))[1] AS origin_id
    FROM (
        SELECT c2.job_id AS copy_id, o.job_id AS origin_id
        FROM user_service.recommend_jobs c2
        JOIN user_service.recommend_jobs o
          ON o.job_id <> c2.job_id
         AND o.source IS DISTINCT FROM 'cache_hit'
         AND o.result_payload IS NOT NULL
         AND (o.result_payload - 'job_id') = (c2.result_payload - 'job_id')
        WHERE c2.source = 'cache_hit'
          AND c2.parent_job_id IS NULL
          AND c2.result_payload IS NOT NULL
    ) pairs
    GROUP BY copy_id
    HAVING count(*) = 1     -- 후보가 하나뿐일 때만 잇는다
) m
WHERE c.job_id = m.copy_id
  AND c.parent_job_id IS NULL;
