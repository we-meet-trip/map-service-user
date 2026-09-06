package map.service.user.nearby;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * hub 의 주변 장소 조회를 부르는 어댑터 (경계 B3).
 *
 * <p>실패하면 빈 목록이다. 주변 정보는 일정에 곁들이는 것이라, 못 받았다고
 * 일정 화면까지 함께 죽으면 안 된다.
 *
 * <p>실패 사유는 종류와 상태 코드만 남긴다. 좌표를 로그에 남기면 사용자가
 * 어디 있었는지가 그대로 기록된다.
 */
@Component
public class HubNearbyClient {

    private static final Logger log = LoggerFactory.getLogger(HubNearbyClient.class);

    private final RestClient client;
    private final map.service.user.global.crypto.LocationSeal seal;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public HubNearbyClient(@Qualifier("hubRestClient") RestClient client,
                           map.service.user.global.crypto.LocationSeal seal,
                           com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.client = client;
        this.seal = seal;
        this.objectMapper = objectMapper;
    }

    /** hub 응답 봉투. 필요한 칸만 받는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Response(List<NearbyPlace> places) {
    }

    /**
     * 한 지점 주변에서 분류로 장소를 찾는다.
     *
     * @param category stay | food | cafe
     * @return 가까운 것부터. 못 받으면 빈 목록
     */
    public List<NearbyPlace> find(double lat, double lng, String category,
                                  int radiusMeters, int size) {
        try {
            com.fasterxml.jackson.databind.JsonNode body = client.get()
                    .uri(b -> {
                        b.path("/v1/places/nearby");
                        if (seal.isEnabled()) b.queryParam("loc", seal.seal(lat, lng));
                        else b.queryParam("lat", lat).queryParam("lng", lng);
                        return b.queryParam("category", category)
                                .queryParam("radius", radiusMeters).queryParam("size", size).build();
                    })
                    .retrieve()
                    .body(com.fasterxml.jackson.databind.JsonNode.class);
            if (body != null && seal.isEnabled()) body = seal.open(body.path("loc").asText());
            Response res = body == null ? null : objectMapper.convertValue(body, Response.class);
            return res == null || res.places() == null ? List.of() : res.places();
        } catch (RestClientResponseException e) {
            log.warn("hub /v1/places/nearby failed category={} status={}",
                    category, e.getStatusCode().value());
            return List.of();
        } catch (RuntimeException e) {
            log.warn("hub /v1/places/nearby failed category={} cause={}",
                    category, e.getClass().getSimpleName());
            return List.of();
        }
    }
}
