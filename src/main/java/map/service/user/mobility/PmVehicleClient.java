package map.service.user.mobility;

import java.nio.charset.StandardCharsets;
import map.service.user.mobility.dto.PmVehiclesResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * PmVehicleClient — hub /v1/mobility/pm-vehicles 호출 어댑터 (경계 B3)
 *
 * client 의 킥보드 요청을 hub 의 조회 엔드포인트로 동기 전달한다. 장소·리뷰·
 * 사진 조회와 같은 RestClient 빈을 재사용한다.
 *
 * BFF 는 결과를 캐시하지 않는다. hub 가 사업자별 결과를 모아 담아 두고 요청
 * 좌표 주변만 잘라 보내므로, 여기에 한 겹 더 두면 위치만 낡는다.
 *
 * client: @Qualifier("hubRestClient") RestClient. base URL/타임아웃은
 *         HubClientConfig 가 구성한다.
 */
@Component
public class PmVehicleClient {

    private final RestClient client;

    /** 오류 응답 본문을 메모리에 읽을 최대 바이트(과대 응답 OOM 방지). */
    private static final int ERROR_BODY_MAX = 4096;

    public PmVehicleClient(@Qualifier("hubRestClient") RestClient client) {
        this.client = client;
    }

    /**
     * hub GET /v1/mobility/pm-vehicles 호출.
     *
     * lat / lng: 기준 좌표. radiusM: 잘라 받을 반경(m).
     * city: 시군구명(선택, null 가능). 발급처가 지역으로 좁혀 받을 수 있다.
     */
    public PmVehiclesResponse fetch(
            double lat, double lng, int radiusM, String city) {
        return client.get()
                .uri(uri -> {
                    uri.path("/v1/mobility/pm-vehicles")
                            .queryParam("lat", lat)
                            .queryParam("lng", lng)
                            .queryParam("radius_m", radiusM);
                    if (city != null && !city.isBlank()) {
                        uri.queryParam("city", city);
                    }
                    return uri.build();
                })
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        (req, res) -> {
                            String body = readBody(res.getBody());
                            throw new PmVehicleException(
                                    res.getStatusCode().value(), body);
                        })
                .body(PmVehiclesResponse.class);
    }

    /**
     * 응답 본문 InputStream 을 UTF-8 문자열로 안전하게 변환.
     *
     * stream 이 null 이거나 IOException 이면 빈 문자열을 반환한다. 진단
     * 요약 용도이므로 ERROR_BODY_MAX(4KB)까지만 읽어 과대 응답이 메모리에
     * 무제한 버퍼링되는 것을 막는다. try-with-resources 로 스트림을 닫는다.
     */
    private static String readBody(java.io.InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (stream) {
            return new String(
                    stream.readNBytes(ERROR_BODY_MAX), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
