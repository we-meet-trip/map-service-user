package map.service.user.chat;

import static org.assertj.core.api.Assertions.assertThat;

import map.service.user.global.config.ChatProperties;
import map.service.user.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

/**
 * ChatConfigTest — 채팅 설정/오류코드 계약 검증
 *
 * ChatProperties 는 설정이 없을 때 안전한 기본값을 갖는지, 그리고 kebab-case 로 준
 * chat.* 키가 camelCase 필드로 올바르게 바인딩되는지 ApplicationContextRunner 로
 * 확인한다(전체 컨텍스트 부팅 없이 프로퍼티 바인딩만 격리 검증).
 *
 * ErrorCode 의 채팅 항목은 클라이언트가 상태코드로 분기하므로, 만료(GONE)·정원 초과
 * (CONFLICT)·권한(FORBIDDEN)·미존재(NOT_FOUND) 매핑을 고정해 회귀를 막는다.
 */
@DisplayName("Chat 설정/오류코드 계약 테스트")
class ChatConfigTest {

    /** ChatProperties 만 등록하는 최소 설정. 전체 애플리케이션 부팅을 피한다. */
    @Configuration
    @EnableConfigurationProperties(ChatProperties.class)
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PropsConfig.class);

    @Test
    @DisplayName("ChatProperties — 설정 없으면 승인된 기본값으로 바인딩")
    void defaults() {
        runner.run(ctx -> {
            ChatProperties p = ctx.getBean(ChatProperties.class);
            assertThat(p.getMaxParticipants()).isEqualTo(10);
            assertThat(p.getExpiryGraceDays()).isEqualTo(7);
            assertThat(p.getHistoryPageSize()).isEqualTo(50);
            assertThat(p.getMaxMessageLength()).isEqualTo(2000);
            assertThat(p.getSendRateLimit()).isEqualTo(20);
            assertThat(p.getSendRateWindowSeconds()).isEqualTo(10);
            assertThat(p.getBroadcastChannel()).isEqualTo("chat:broadcast");
            assertThat(p.getWsEndpoint()).isEqualTo("/ws/chat");
        });
    }

    @Test
    @DisplayName("ChatProperties — kebab-case chat.* 키가 camelCase 필드로 바인딩")
    void kebabKeyBinding() {
        runner.withPropertyValues(
                "chat.max-participants=5",
                "chat.expiry-grace-days=3",
                "chat.history-page-size=20",
                "chat.max-message-length=500",
                "chat.send-rate-limit=7",
                "chat.send-rate-window-seconds=15",
                "chat.broadcast-channel=chat:test",
                "chat.ws-endpoint=/ws/test",
                "chat.invite-base-url=https://example.invalid/x/"
        ).run(ctx -> {
            ChatProperties p = ctx.getBean(ChatProperties.class);
            assertThat(p.getMaxParticipants()).isEqualTo(5);
            assertThat(p.getExpiryGraceDays()).isEqualTo(3);
            assertThat(p.getHistoryPageSize()).isEqualTo(20);
            assertThat(p.getMaxMessageLength()).isEqualTo(500);
            assertThat(p.getSendRateLimit()).isEqualTo(7);
            assertThat(p.getSendRateWindowSeconds()).isEqualTo(15);
            assertThat(p.getBroadcastChannel()).isEqualTo("chat:test");
            assertThat(p.getWsEndpoint()).isEqualTo("/ws/test");
            assertThat(p.getInviteBaseUrl()).isEqualTo("https://example.invalid/x/");
        });
    }

    @Test
    @DisplayName("ErrorCode — 채팅 항목의 HTTP 상태 계약 고정")
    void chatErrorStatusContract() {
        assertThat(ErrorCode.CHAT_ROOM_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.CHAT_SCHEDULE_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.CHAT_NOT_OWNER.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.CHAT_NOT_PARTICIPANT.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.CHAT_ROOM_EXPIRED.getHttpStatus()).isEqualTo(HttpStatus.GONE);
        assertThat(ErrorCode.CHAT_ROOM_FULL.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CHAT_ALREADY_PARTICIPANT.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CHAT_INVITE_INVALID.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.CHAT_INVITE_REVOKED.getHttpStatus()).isEqualTo(HttpStatus.GONE);
    }
}
