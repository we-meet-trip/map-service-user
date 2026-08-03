-- =============================================================================
-- V008__user_preferences.sql — users 에 가입 시 받는 개인 정보·취향 4컬럼 추가
--
-- 책임: 회원가입 화면이 받는 값을 서버가 실제로 보관할 자리를 만든다.
--       화면은 이미 생년월일·성별·관심사를 받고 있는데 서버에는 담을 곳이
--       없어, 보내도 조용히 버려지고 앱을 끄면 사라진다. 그래서 다시 열었을
--       때 가입 때 고른 값이 하나도 남아 있지 않다.
--
-- 스키마: user_service
--
-- 컬럼(전부 nullable — 이 마이그레이션 이전에 가입한 행은 값이 없고,
--      가입 요청에서도 선택 항목이다):
-- - birth_date : 생년월일
-- - gender     : 성별. 화면이 보내는 표기를 그대로 담는다
-- - interests  : 관심사 목록(JSON 배열)
-- - themes     : 여행 테마 목록(JSON 배열)
--
-- 관심사·테마를 JSON 으로 담는 이유: 개수가 정해지지 않은 문자열 목록이고
-- 조건 검색 대상이 아니라 통째로 읽고 통째로 쓴다. 별도 테이블로 나누면
-- 읽을 때마다 조인이 붙는데 얻는 것이 없다. 이 레포의 다른 목록형 값도
-- 같은 방식으로 담는다.
--
-- 값의 종류는 DB 에서 제한하지 않는다. 목록이 화면에 있고 거기서 늘거나
-- 줄어드는데, 같은 목록을 DB 제약으로 복제해 두면 화면이 바뀔 때마다
-- 마이그레이션이 따라붙고 어긋나는 순간 가입이 막힌다. 개수·길이 상한은
-- 요청 검증에서 건다.
--
-- 되돌리기: ALTER TABLE user_service.users
--             DROP COLUMN birth_date, DROP COLUMN gender,
--             DROP COLUMN interests, DROP COLUMN themes;
-- =============================================================================

ALTER TABLE user_service.users
    ADD COLUMN IF NOT EXISTS birth_date DATE,
    ADD COLUMN IF NOT EXISTS gender VARCHAR(16),
    ADD COLUMN IF NOT EXISTS interests JSONB,
    ADD COLUMN IF NOT EXISTS themes JSONB;
