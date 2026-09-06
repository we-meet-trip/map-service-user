package map.service.user.moderation;

import com.fasterxml.jackson.databind.*;
import java.time.OffsetDateTime;
import java.util.Objects;
import map.service.user.chat.entity.ChatMessage;
import map.service.user.chat.repository.ChatMessageRepository;
import map.service.user.global.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatModerationGuard {
    private final UserBlockRepository blocks;
    private final ChatRestrictionRepository restrictions;
    private final ChatMessageRepository messages;
    private final ChatContentPolicy policy;
    private final ObjectMapper mapper;
    public ChatModerationGuard(UserBlockRepository blocks, ChatRestrictionRepository restrictions,
            ChatMessageRepository messages, ChatContentPolicy policy, ObjectMapper mapper) {
        this.blocks=blocks; this.restrictions=restrictions; this.messages=messages;
        this.policy=policy; this.mapper=mapper;
    }
    @Transactional(readOnly=true)
    public void assertCanSend(Long user, String text) {
        if (restrictions.existsByUserIdAndRestrictedUntilAfter(user, OffsetDateTime.now()))
            throw new CustomException(ErrorCode.CHAT_RESTRICTED);
        if (policy.prohibited(text)) throw new CustomException(ErrorCode.CHAT_CONTENT_REJECTED);
    }
    public boolean blocked(Long viewer, Long actor) {
        return actor!=null && !actor.equals(viewer) && blocks.countBetween(viewer, actor)>0;
    }
    public String visibleText(String content) { return policy.visibleText(content); }

    /** The recipient is derived from the authenticated session, never an event field.
     * Re-read persisted content: queued Redis events cannot resurrect hidden/erased text. */
    @Transactional(readOnly=true)
    public boolean mayDeliver(Long viewer, Long room, Object payload) {
        try {
            JsonNode event=payload instanceof byte[] bytes ? mapper.readTree(bytes)
                    : payload instanceof String value ? mapper.readTree(value) : mapper.valueToTree(payload);
            if (event==null || !event.isObject() || !event.path("room_id").isIntegralNumber()
                    || event.path("room_id").longValue()!=room) return false;
            JsonNode data=event.path("data");
            return switch(event.path("type").asText()) {
                case "MESSAGE", "SYSTEM" -> {
                    if (!data.path("seq").isIntegralNumber()) yield false;
                    ChatMessage current=messages.findReportable(room, viewer, data.path("seq").longValue()).orElse(null);
                    if (current==null || current.isModerationHidden()) yield false;
                    if (current.getType()==ChatMessage.MessageType.TEXT) {
                        yield "MESSAGE".equals(event.path("type").asText()) && current.getSenderId()!=null
                                && data.path("sender_id").isIntegralNumber()
                                && data.path("sender_id").longValue()==current.getSenderId()
                                && !blocked(viewer, current.getSenderId()) && current.getContent()!=null
                                && !policy.prohibited(current.getContent())
                                && Objects.equals(current.getContent(), data.path("content").asText(null));
                    }
                    yield "SYSTEM".equals(event.path("type").asText())
                            && (current.getContent()!=null || current.getSystemPayload()!=null)
                            && Objects.equals(current.getContent(), data.path("content").asText(null))
                            && Objects.equals(current.getSystemPayload(), data.get("system_payload")==null || data.get("system_payload").isNull() ? null : data.get("system_payload"));
                }
                case "READ", "TYPING", "PRESENCE" -> data.path("user_id").isIntegralNumber()
                        && data.path("user_id").longValue()>0 && !blocked(viewer, data.path("user_id").longValue());
                case "ROOM_CLOSED" -> data.isNull();
                case "MESSAGE_REMOVED" -> data.path("seq").isIntegralNumber() && data.path("seq").longValue()>0;
                default -> false;
            };
        } catch (Exception invalid) { return false; }
    }
}
