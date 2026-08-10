package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * InvitePreview — 초대 링크 미리보기 응답
 *
 * 링크로 진입한 사용자가 참가 전에 방을 확인할 때 반환된다. 참가는 일으키지 않으며,
 * joinable 은 지금 이 링크로 참가할 수 있는지(미폐기·미만료·정원 여유) 여부다.
 * expiresAt 은 링크 만료와 방 만료 중 이른 쪽으로, 발급 응답과 같은 의미다.
 *
 * 로그인하지 않은 사람도 받을 수 있는 응답이므로, 방을 특정할 수 있는 최소한만 담는다.
 */
public record InvitePreview(
        @JsonProperty("room_id") long roomId,
        String title,
        @JsonProperty("participant_count") int participantCount,
        boolean joinable,
        @JsonProperty("expires_at") OffsetDateTime expiresAt
) {
}
