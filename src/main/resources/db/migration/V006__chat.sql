-- =============================================================================
-- V006__chat.sql — 실시간 채팅방 3개 테이블 + 인덱스 생성
--
-- 책임: 저장된 여행 일정(schedules)에 1:1로 묶이는 채팅방과 참가자·메시지를
--       user_service 스키마에 영속화한다. 본 테이블은 방/참가/메시지 기록의
--       durable 저장소로, 메시지의 실시간 전달은 애플리케이션 계층이 처리하고
--       여기서는 기록·상태·읽음 포인터만 보관한다.
--
-- 스키마: user_service
--
-- chat_rooms — schedule_id 에 1:1(UNIQUE)로 앵커된 방
-- - room_id              : PK, BIGSERIAL
-- - schedule_id          : 앵커 일정 (UNIQUE, FK→schedules ON DELETE CASCADE)
-- - owner_id             : 소유자 = schedules.user_id 스냅샷 (NOT NULL)
-- - title                : 일정 제목 스냅샷
-- - next_seq             : 룸별 단조 메시지 카운터 (allocate 시 +1)
-- - invite_token_hash    : 초대 원시토큰의 SHA-256 hex (NULL = 활성 링크 없음)
-- - invite_token_version : 재발급/폐기 때마다 증가 (구 링크 무효화)
-- - invite_revoked       : 링크 폐기 여부
-- - expires_at           : = date_end + 7일 23:59:59 Asia/Seoul 스냅샷 (NOT NULL)
-- - read_only            : sweep 가 만료 시 전환. 실검사(expires_at)가 authoritative
-- - created_at           : 생성 시각 (NOT NULL, 기본값 now())
--
-- chat_participants — 실제 인증 user_id 참가자만 (익명 게스트 없음)
-- - role   : OWNER | MEMBER
-- - status : ACTIVE | LEFT | KICKED (기록 유지, 상태로 소프트 제거)
-- - last_read_message_seq : 카톡식 안읽은 인원수 파생을 위한 읽음 포인터
-- - UNIQUE (room_id, user_id) : 사용자당 한 참가행 (멱등 join·상한 검사 근거)
--
-- chat_messages — 텍스트 + 시스템 메시지
-- - seq    : 룸별 단조 (chat_rooms.next_seq 에서 발급)
-- - sender_id : SYSTEM 메시지는 NULL
-- - type   : TEXT | SYSTEM
-- - system_payload : 카드 payload(일정 보러가기 등), nullable JSONB
-- - UNIQUE (room_id, seq) : 중복 seq 금지 + 커서 페이징 인덱스 지원
--
-- 멱등성:
-- - CREATE TABLE / INDEX 모두 IF NOT EXISTS 로 재실행 안전.
-- =============================================================================

CREATE TABLE IF NOT EXISTS user_service.chat_rooms (
    room_id              BIGSERIAL   PRIMARY KEY,
    schedule_id          BIGINT      NOT NULL UNIQUE
                             REFERENCES user_service.schedules(schedule_id) ON DELETE CASCADE,
    owner_id             BIGINT      NOT NULL,
    title                TEXT,
    next_seq             BIGINT      NOT NULL DEFAULT 0,
    invite_token_hash    VARCHAR(64),
    invite_token_version INT         NOT NULL DEFAULT 0,
    invite_revoked       BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at           TIMESTAMPTZ NOT NULL,
    read_only            BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chat_rooms_owner ON user_service.chat_rooms(owner_id);
CREATE INDEX IF NOT EXISTS idx_chat_rooms_invite_hash ON user_service.chat_rooms(invite_token_hash)
    WHERE invite_token_hash IS NOT NULL;

CREATE TABLE IF NOT EXISTS user_service.chat_participants (
    id                    BIGSERIAL   PRIMARY KEY,
    room_id               BIGINT      NOT NULL REFERENCES user_service.chat_rooms(room_id) ON DELETE CASCADE,
    user_id               BIGINT      NOT NULL,
    role                  VARCHAR(8)  NOT NULL DEFAULT 'MEMBER',
    status                VARCHAR(8)  NOT NULL DEFAULT 'ACTIVE',
    last_read_message_seq BIGINT      NOT NULL DEFAULT 0,
    joined_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_chat_participant UNIQUE (room_id, user_id),
    CONSTRAINT chk_chat_role   CHECK (role   IN ('OWNER', 'MEMBER')),
    CONSTRAINT chk_chat_status CHECK (status IN ('ACTIVE', 'LEFT', 'KICKED'))
);

CREATE INDEX IF NOT EXISTS idx_chat_participants_user ON user_service.chat_participants(user_id);

CREATE TABLE IF NOT EXISTS user_service.chat_messages (
    id             BIGSERIAL   PRIMARY KEY,
    room_id        BIGINT      NOT NULL REFERENCES user_service.chat_rooms(room_id) ON DELETE CASCADE,
    seq            BIGINT      NOT NULL,
    sender_id      BIGINT,
    type           VARCHAR(8)  NOT NULL DEFAULT 'TEXT',
    content        TEXT,
    system_payload JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_chat_message_seq UNIQUE (room_id, seq),
    CONSTRAINT chk_chat_msg_type CHECK (type IN ('TEXT', 'SYSTEM'))
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_room_seq ON user_service.chat_messages(room_id, seq DESC);
