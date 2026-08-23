-- V016__schedule_arrivals.sql — 실제로 그 자리에 갔는지
--
-- 왜 필요한가:
--   저장은 "가겠다" 는 뜻이고 도착은 "갔다" 는 뜻이다. 둘은 다르다 — 저장해
--   놓고 안 간 자리와 실제로 들른 자리를 구분하지 못하면, 저장만 정답으로
--   쓰는 학습은 "고르긴 했지만 가지 않은 곳" 까지 좋은 것으로 배운다.
--
-- 무엇을 담지 않는가:
--   좌표를 담지 않는다. 어디에 있었는지는 일정에 이미 적혀 있고, 그것과
--   시각을 맞추면 필요한 것은 다 나온다. 위치 원점을 한 벌 더 두면 담는
--   것에 비해 다루기가 훨씬 무거워진다. 판정은 기기에서 끝낸다.
--
-- 왜 (일정, 일차, 순번) 이 유일한가:
--   기기는 위치가 들어올 때마다 판정하므로 같은 자리를 여러 번 알릴 수 있다.
--   처음 닿은 시각만 남기면 되고, 뒤에 오는 것은 조용히 버린다.
--
-- timeline_status 를 함께 두는 이유:
--   계획한 시각이 확실치 않은 일정("unverified")이 있다. 그때는 계획-실제
--   시간차를 비교해도 뜻이 없으므로, 읽는 쪽이 갈라 볼 수 있어야 한다.

CREATE TABLE IF NOT EXISTS user_service.schedule_arrivals (
    schedule_id     BIGINT      NOT NULL
        REFERENCES user_service.schedules(schedule_id) ON DELETE CASCADE,
    -- 컬럼명을 day 로 두지 않는다. 어떤 DB 는 그것을 날짜 함수 이름으로
    -- 읽어 테이블을 만들다 문법 오류를 낸다(실측: 시험이 쓰는 H2).
    trip_day        INT         NOT NULL,
    stop_order      INT         NOT NULL,
    arrived_at      TIMESTAMPTZ NOT NULL,
    timeline_status VARCHAR(16),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (schedule_id, trip_day, stop_order)
);

COMMENT ON TABLE user_service.schedule_arrivals IS
    '일정의 각 방문지에 실제로 닿은 시각. 좌표는 담지 않는다(일정에 이미 있다).';
