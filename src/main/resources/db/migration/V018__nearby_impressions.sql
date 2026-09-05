-- V017__nearby_impressions.sql — 주변 장소를 보여 준 것과 눌린 것
--
-- 왜 필요한가:
--   지금 남는 사용자 신호는 "저장했다" 와 "갔다" 뿐이다. 둘 다 일정을 다 만든
--   뒤의 일이라, 그 전에 무엇을 보고 무엇에 눈길이 갔는지는 남지 않는다.
--   보여 준 것과 눌린 것을 함께 남기면 그 사이가 채워진다.
--
-- 왜 보여 준 것도 남기나:
--   눌린 것만 남기면 "안 눌렀다" 가 "안 보였다" 인지 "보고 안 골랐다" 인지
--   구분되지 않는다. 반례가 성립하려면 보여 준 목록이 있어야 한다.
--
-- 두 표로 나누지 않는 이유:
--   한 번 보여 준 묶음에 눌린 것을 표시하는 편이 조인 없이 읽힌다. 누른 시각만
--   따로 채운다.

CREATE TABLE IF NOT EXISTS user_service.nearby_impressions (
    id           BIGSERIAL   PRIMARY KEY,
    schedule_id  BIGINT      NOT NULL
        REFERENCES user_service.schedules(schedule_id) ON DELETE CASCADE,
    -- 어느 방문지 주변을 본 것인지. 일정 payload 의 방문 순서와 같은 뜻이다.
    trip_day     INT         NOT NULL,
    stop_order   INT         NOT NULL,
    -- stay | food | cafe. 발급처 분류 코드가 아니라 우리 말로 담는다.
    category     VARCHAR(16) NOT NULL,
    content_id   VARCHAR(64) NOT NULL,
    -- 목록에서 몇 번째로 보였는지. 위에 있어서 눌리기 쉬웠던 효과를 보정한다.
    rank         INT         NOT NULL,
    shown_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 누른 적 없으면 비어 있다.
    clicked_at   TIMESTAMPTZ,
    CONSTRAINT uq_nearby_impression
        UNIQUE (schedule_id, trip_day, stop_order, category, content_id)
);

-- 학습 자료를 뽑을 때 일정 단위로 훑는다.
CREATE INDEX IF NOT EXISTS idx_nearby_impressions_schedule
    ON user_service.nearby_impressions(schedule_id);

-- 오래된 것을 정리할 때 기한으로 훑는다.
CREATE INDEX IF NOT EXISTS idx_nearby_impressions_shown_at
    ON user_service.nearby_impressions(shown_at);

COMMENT ON TABLE user_service.nearby_impressions IS
    '일정 방문지 주변으로 보여 준 장소와 그중 눌린 것. 좌표는 담지 않는다.';
