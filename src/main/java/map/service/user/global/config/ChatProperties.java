package map.service.user.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ChatProperties — 채팅 도메인 런타임 설정 값 바인딩
 *
 * application.yml 의 chat.* 키를 필드로 바인딩한다. 모든 필드에 기본값을 두어
 * 설정이 없어도 안전한 값으로 동작하며, 배포 환경에서 각 키를 오버라이드할 수 있다.
 *
 * 각 값의 쓰임:
 * - maxParticipants       : 방 하나에 들어갈 수 있는 ACTIVE 참가자 상한. join 시
 *                           방 행을 잠근 상태에서 현재 인원과 비교해 초과를 막는다.
 * - expiryGraceDays       : 여행 종료일(date_end) 이후 며칠까지 전송을 허용할지.
 *                           방 생성 시 이 값으로 만료 시각을 계산해 스냅샷한다.
 * - historyPageSize       : 메시지 히스토리 한 페이지 최대 건수(커서 페이징 상한).
 * - inviteBaseUrl         : 초대 링크 URL 을 만들 때 토큰 앞에 붙이는 기준 주소.
 *                           배포 시 실제 딥링크 주소로 반드시 오버라이드해야 한다.
 * - maxMessageLength      : 텍스트 메시지 한 건의 최대 글자 수. 초과 시 전송을 거부한다.
 * - sendRateLimit         : sendRateWindowSeconds 창 동안 한 사용자가 보낼 수 있는
 *                           최대 메시지 수(전송 폭주 억제).
 * - sendRateWindowSeconds : 전송 레이트리밋을 계산하는 시간 창(초).
 * - inviteRateLimit       : inviteRateWindowSeconds 창 동안 한 사용자가 발급할 수 있는
 *                           최대 초대 링크 수(발급 남용 억제).
 * - inviteRateWindowSeconds : 초대 발급 레이트리밋을 계산하는 시간 창(초).
 * - broadcastChannel      : 인스턴스 간 메시지 팬아웃에 쓰는 Redis 발행/구독 채널명.
 * - wsEndpoint            : 클라이언트가 실시간 연결을 맺는 WebSocket 핸드셰이크 경로.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "chat")
public class ChatProperties {

    private int maxParticipants = 10;

    private int expiryGraceDays = 7;

    private int historyPageSize = 50;

    private String inviteBaseUrl = "https://example.invalid/chat/invite/";

    private int maxMessageLength = 2000;

    private int sendRateLimit = 20;

    private int sendRateWindowSeconds = 10;

    private int inviteRateLimit = 10;

    private int inviteRateWindowSeconds = 60;

    private String broadcastChannel = "chat:broadcast";

    private String wsEndpoint = "/ws/chat";
}
