package map.service.user.chat.web;

import jakarta.validation.Valid;
import java.util.List;
import map.service.user.chat.dto.CreateRoomRequest;
import map.service.user.chat.dto.RoomResponse;
import map.service.user.chat.dto.RoomSummary;
import map.service.user.chat.service.ChatRealtimeService;
import map.service.user.chat.service.ChatRoomService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ChatRoomController — 채팅방 조회/생성 HTTP 진입점
 *
 * /api/v1/chat 하위의 방 생성·조회 엔드포인트를 노출하고 동작은 ChatRoomService 로
 * 위임한다. 소유자 식별은 @AuthenticationPrincipal 로 주입되는 userId 를 그대로 넘긴다.
 *
 * 엔드포인트:
 * - POST /rooms                       → 생성 또는 조회(신규 201 / 기존 200)
 * - GET  /rooms/{roomId}              → 방 단건
 * - GET  /rooms/by-schedule/{id}      → 일정으로 방 조회
 * - GET  /rooms                       → 내 채팅방 목록
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatRoomController {

    private final ChatRoomService roomService;
    private final ChatRealtimeService realtimeService;

    public ChatRoomController(ChatRoomService roomService, ChatRealtimeService realtimeService) {
        this.roomService = roomService;
        this.realtimeService = realtimeService;
    }

    /**
     * 방 생성 또는 조회. 서비스가 이번 호출에서 새로 만들었는지(created) 알려주면 그에
     * 따라 201(신규)/200(기존) 상태로 방 응답을 돌려준다.
     */
    @PostMapping("/rooms")
    public ResponseEntity<RoomResponse> createRoom(
            @Valid @RequestBody CreateRoomRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        ChatRoomService.RoomResult result = roomService.createOrGetRoom(request.scheduleId(), userId);
        if (result.created()) {
            // 새로 만든 방에는 상단에 "일정 보러가기" 카드를 남긴다.
            realtimeService.emitItineraryCard(result.response().roomId(), result.response().scheduleId());
        }
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping("/rooms/{roomId}")
    public RoomResponse getRoom(@PathVariable Long roomId, @AuthenticationPrincipal Long userId) {
        return roomService.getRoom(roomId, userId);
    }

    @GetMapping("/rooms/by-schedule/{scheduleId}")
    public RoomResponse getRoomBySchedule(
            @PathVariable Long scheduleId,
            @AuthenticationPrincipal Long userId
    ) {
        return roomService.getRoomBySchedule(scheduleId, userId);
    }

    @GetMapping("/rooms")
    public List<RoomSummary> listRooms(@AuthenticationPrincipal Long userId) {
        return roomService.listMyRooms(userId);
    }
}
