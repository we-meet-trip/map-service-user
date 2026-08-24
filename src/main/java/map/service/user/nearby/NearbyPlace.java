package map.service.user.nearby;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 일정 방문지 주변에 있는 장소 하나.
 *
 * <p>hub 응답에는 이보다 많은 칸이 오지만 화면이 쓰는 것만 받는다. 늘어난 칸을
 * 그대로 흘리면 계약이 조용히 넓어져, 나중에 hub 가 무엇을 빼도 되는지 알 수
 * 없게 된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NearbyPlace(
        @JsonProperty("content_id") String contentId,
        String name,
        String address,
        Double lat,
        Double lng,
        String category,
        /** 목록에서 몇 번째인지. 위에 있어서 눌리기 쉬웠던 효과를 보정하는 데 쓴다. */
        Integer rank,
        @JsonProperty("place_url") String placeUrl
) {
}
