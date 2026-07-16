package map.service.user.chat.service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import map.service.user.chat.ws.ChatBroadcastRelay;
import map.service.user.chat.ws.ChatEventEnvelope;
import org.springframework.stereotype.Service;

/**
 * ChatPresenceService — 접속(프레즌스) 상태 관리 (단일 인스턴스 기준)
 *
 * WebSocket 세션의 구독/종료를 받아 "누가 어느 방에 접속 중인지"를 인메모리로 관리하고,
 * 상태 변화가 있을 때만 PRESENCE 이벤트를 브로드캐스트한다.
 *
 * 온라인 여부의 진실원은 이 프로세스의 인메모리 카운터다. 세션 종료(정상/비정상 모두
 * 컨테이너가 소켓 닫힘을 감지하면 SessionDisconnectEvent 로 통지됨) 시 카운터가 정리되며,
 * 프로세스가 죽으면 카운터도 함께 사라지므로(재기동 시 아무도 접속 안 한 상태) 유령
 * 온라인이 영구히 남지 않는다.
 *
 * 다기기 처리: 같은 사용자가 여러 세션(기기)으로 접속할 수 있으므로 (방,사용자)별 세션
 * 수를 세어, 첫 세션이 들어올 때만 online=true 를, 마지막 세션이 나갈 때만 online=false 를
 * 보낸다. (다중 인스턴스 접속 집계는 후속 과제이며 현재 배포는 단일 인스턴스다.)
 */
@Service
public class ChatPresenceService {

    /** 세션 식별자 → 그 세션이 접속 중인 (방,사용자) 집합. */
    private final ConcurrentHashMap<String, Set<RoomUser>> sessionSubscriptions =
            new ConcurrentHashMap<>();
    /** (방,사용자) → 현재 접속 세션 수. 0 이 되면 항목이 제거된다(= 오프라인). */
    private final ConcurrentHashMap<RoomUser, Integer> presenceCounts = new ConcurrentHashMap<>();

    private final ChatBroadcastRelay relay;

    public ChatPresenceService(ChatBroadcastRelay relay) {
        this.relay = relay;
    }

    /**
     * 세션이 방 토픽을 구독했을 때 호출한다. 이 세션이 해당 (방,사용자)에 처음 들어온
     * 것이면 접속 수를 1 로 올리고 online=true 를 브로드캐스트한다. 같은 세션이 같은 방을
     * 중복 구독하면 무시한다.
     */
    public void onSubscribe(String sessionId, long roomId, long userId) {
        RoomUser roomUser = new RoomUser(roomId, userId);
        boolean added = sessionSubscriptions
                .computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet())
                .add(roomUser);
        if (!added) {
            return;
        }
        int count = presenceCounts.merge(roomUser, 1, Integer::sum);
        if (count == 1) {
            relay.publish(ChatEventEnvelope.presence(roomId, userId, true));
        }
    }

    /**
     * 세션이 종료됐을 때 호출한다. 그 세션이 접속해 있던 각 (방,사용자)에 대해 접속 수를
     * 줄이고, 마지막 세션이었으면 online=false 를 브로드캐스트한다.
     */
    public void onDisconnect(String sessionId) {
        Set<RoomUser> subscriptions = sessionSubscriptions.remove(sessionId);
        if (subscriptions == null) {
            return;
        }
        for (RoomUser roomUser : subscriptions) {
            Integer remaining = presenceCounts.compute(roomUser,
                    (key, value) -> (value == null || value <= 1) ? null : value - 1);
            if (remaining == null) {
                relay.publish(ChatEventEnvelope.presence(roomUser.roomId(), roomUser.userId(), false));
            }
        }
    }

    /** 방의 현재 온라인 사용자 식별자 목록(인메모리 카운터 기준, 접속 수 1 이상인 사용자). */
    public List<Long> onlineUserIds(long roomId) {
        return presenceCounts.keySet().stream()
                .filter(roomUser -> roomUser.roomId() == roomId)
                .map(RoomUser::userId)
                .toList();
    }

    /** (방,사용자) 쌍. 접속 수 집계의 키로 쓰인다. */
    public record RoomUser(long roomId, long userId) {
    }
}
