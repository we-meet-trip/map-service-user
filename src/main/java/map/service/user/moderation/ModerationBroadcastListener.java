package map.service.user.moderation;

import java.util.Map;
import map.service.user.chat.ws.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
public class ModerationBroadcastListener {
    private final ChatBroadcastRelay relay;
    public ModerationBroadcastListener(ChatBroadcastRelay relay) { this.relay=relay; }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void removed(ModerationService.MessageRemoved event) {
        relay.publish(ChatEventEnvelope.of("MESSAGE_REMOVED",event.roomId(),Map.of("seq",event.seq())));
    }
}
