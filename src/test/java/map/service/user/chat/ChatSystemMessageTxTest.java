package map.service.user.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import map.service.user.chat.entity.ChatMessage;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatMessageRepository;
import map.service.user.chat.repository.ChatRoomRepository;
import map.service.user.chat.service.ChatSystemMessageService;
import map.service.user.chat.ws.ChatBroadcastRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * ChatSystemMessageTxTest — 시스템 메시지 발행의 트랜잭션 경계 회귀 테스트
 *
 * 실제 프록시 빈을 "주변 트랜잭션 없이" 호출해, 공개 진입점(emitItineraryCard)이 스스로
 * 트랜잭션을 여는지 검증한다. @Transactional 이 내부 자기호출 메서드에만 붙어 있으면
 * 락 조회(findByIdForUpdate)가 "no transaction in progress"로 실패하는데(런타임에서 방
 * 생성이 500이 되던 결함), 단위 테스트(@DataJpaTest)는 자체 트랜잭션을 제공해 이를 놓친다.
 * 따라서 전체 컨텍스트에서 프록시를 통해 호출하는 이 테스트가 회귀 방지선이다.
 *
 * 브로드캐스트는 실제 Redis 를 피하려고 릴레이를 목으로 대체한다(커밋 후 발행은 별개 관심사).
 */
// webEnvironment=MOCK(기본): 보안 필터체인이 MVC 인프라(mvcHandlerMappingIntrospector)를
// 필요로 하므로 목 서블릿 환경을 갖춘다. 실제 HTTP 요청은 하지 않고 서비스 빈만 호출한다.
@SpringBootTest
@ActiveProfiles("test")
// 이 @SpringBootTest 는 트랜잭션을 걸지 않고 커밋하며 create-drop 을 돌리므로, 공유 testdb 를
// 오염시키지 않도록 전용 인메모리 DB 를 사용한다(다른 @DataJpaTest 의 스키마와 격리).
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:chattxdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                + "DB_CLOSE_ON_EXIT=FALSE;INIT=CREATE SCHEMA IF NOT EXISTS user_service")
@DisplayName("시스템 메시지 트랜잭션 경계 회귀 테스트")
class ChatSystemMessageTxTest {

    @Autowired private ChatSystemMessageService systemMessageService;
    @Autowired private ChatRoomRepository roomRepository;
    @Autowired private ChatMessageRepository messageRepository;

    @MockitoBean private ChatBroadcastRelay relay;

    @Test
    @DisplayName("주변 트랜잭션 없이 프록시로 호출해도 트랜잭션을 열어 시스템 메시지 저장")
    void emitItineraryCard_opensOwnTransaction() {
        ChatRoom room = roomRepository.save(
                new ChatRoom(424242L, 1L, "방", OffsetDateTime.now().plusDays(8)));
        Long roomId = room.getRoomId();

        // 트랜잭션 데코레이션 없이 호출한다. 진입점이 트랜잭션을 열지 못하면 락 조회에서 예외.
        systemMessageService.emitItineraryCard(roomId, 424242L);

        ChatMessage message = messageRepository.findTopByRoomIdOrderBySeqDesc(roomId).orElseThrow();
        assertThat(message.getType()).isEqualTo(ChatMessage.MessageType.SYSTEM);
        assertThat(message.getSenderId()).isNull();
        assertThat(message.getSystemPayload().get("kind").asText()).isEqualTo("VIEW_ITINERARY");
    }
}
