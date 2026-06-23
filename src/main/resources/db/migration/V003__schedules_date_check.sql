-- =============================================================================
-- V003__schedules_date_check.sql — schedules 날짜 범위 CHECK 제약 추가
--
-- 책임: user_service.schedules 에 date_start <= date_end 불변식을 DB 레벨로 강제.
--       (역전된 날짜 범위 저장 차단. 애플리케이션 계층의 InvalidScheduleDateException
--        400 검증과 이중 방어.)
--
-- 분리 이유: V002 는 이미 배포/적용되었을 수 있으므로(Flyway 마이그레이션 불변성),
--           기존 V002 본문을 수정하지 않고 신규 V003 으로 ALTER 한다.
--
-- 멱등성: 동일 이름 제약이 이미 있으면 건너뛴다(pg_constraint 검사).
-- =============================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_date_range'
          AND conrelid = 'user_service.schedules'::regclass
    ) THEN
        ALTER TABLE user_service.schedules
            ADD CONSTRAINT chk_date_range CHECK (date_start <= date_end);
    END IF;
END $$;
