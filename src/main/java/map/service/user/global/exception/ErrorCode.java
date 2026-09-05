package map.service.user.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Auth — Email
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "AUTH_001", "이미 사용 중인 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_002", "이메일 또는 비밀번호가 올바르지 않습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTH_003", "사용자를 찾을 수 없습니다."),

    // Auth — JWT
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "JWT_001", "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "JWT_002", "만료된 토큰입니다."),
    BLACKLISTED_TOKEN(HttpStatus.UNAUTHORIZED, "JWT_003", "로그아웃된 토큰입니다."),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "JWT_004", "refresh token을 찾을 수 없습니다."),
    REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "JWT_005", "이미 무효화된 refresh token입니다."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "JWT_006", "만료된 refresh token입니다."),

    // Auth — Kakao
    KAKAO_TOKEN_EXCHANGE_FAILED(HttpStatus.BAD_GATEWAY, "KAKAO_001", "카카오 인가 코드 교환에 실패했습니다."),
    KAKAO_USER_INFO_FAILED(HttpStatus.BAD_GATEWAY, "KAKAO_002", "카카오 사용자 정보 조회에 실패했습니다."),
    KAKAO_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "KAKAO_003", "카카오 로그인이 아직 열리지 않았습니다."),

    // Rate Limit
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "RATE_001", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),

    // Recommend
    RESEARCH_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "RECOMMEND_001", "재추천 한도(일 3회)를 초과했습니다. KST 자정 이후 다시 시도해주세요."),
    RECOMMEND_NOT_OWNER(HttpStatus.FORBIDDEN, "RECOMMEND_002", "본인이 만든 추천만 수정할 수 있습니다."),
    RECOMMEND_EDIT_INCONSISTENT(HttpStatus.BAD_REQUEST, "RECOMMEND_003",
            "방문 순서나 이동 구간이 장소 목록과 맞지 않습니다."),

    // Chat — 채팅방 생성·참가·전송 과정의 실패 사유. HTTP 상태는 클라이언트 처리 분기의 계약값이다.
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT_001", "채팅방을 찾을 수 없습니다."),
    CHAT_SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT_002", "채팅방을 만들 일정을 찾을 수 없습니다."),
    CHAT_NOT_OWNER(HttpStatus.FORBIDDEN, "CHAT_003", "채팅방 소유자만 수행할 수 있습니다."),
    CHAT_NOT_PARTICIPANT(HttpStatus.FORBIDDEN, "CHAT_004", "채팅방 참가자가 아닙니다."),
    CHAT_ROOM_EXPIRED(HttpStatus.GONE, "CHAT_005", "보관 전용으로 전환된 채팅방입니다. 메시지를 보낼 수 없습니다."),
    CHAT_ROOM_FULL(HttpStatus.CONFLICT, "CHAT_006", "채팅방 정원이 가득 찼습니다."),
    CHAT_ALREADY_PARTICIPANT(HttpStatus.CONFLICT, "CHAT_007", "이미 참가한 채팅방입니다."),
    CHAT_INVITE_INVALID(HttpStatus.NOT_FOUND, "CHAT_008", "유효하지 않은 초대 링크입니다."),
    CHAT_INVITE_REVOKED(HttpStatus.GONE, "CHAT_009", "만료되었거나 폐기된 초대 링크입니다."),
    CHAT_KICKED(HttpStatus.FORBIDDEN, "CHAT_010", "내보내진 채팅방에는 다시 참가할 수 없습니다."),
    CHAT_MESSAGE_INVALID(HttpStatus.BAD_REQUEST, "CHAT_011", "메시지 내용이 비어 있거나 허용 길이를 초과했습니다."),

    // Common
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_001", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
