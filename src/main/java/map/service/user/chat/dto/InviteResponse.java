package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * InviteResponse — 초대 링크 발급 응답
 *
 * 소유자가 링크를 발급/재발급할 때만 반환된다. token 은 원시 초대 토큰으로, 서버는
 * 해시만 저장하므로 이 평문은 이 응답에서 한 번만 노출된다. url 은 token 을 기준 주소
 * 뒤에 붙인 완성 링크, version 은 재발급마다 증가하는 버전(구 링크 무효화 확인용),
 * expiresAt 은 방과 동일한 만료 시각이다.
 */
public record InviteResponse(
        String token,
        String url,
        int version,
        @JsonProperty("expires_at") OffsetDateTime expiresAt
) {
}
