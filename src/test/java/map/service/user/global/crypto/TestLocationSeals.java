package map.service.user.global.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import map.service.user.global.config.LocationWireProperties;
import org.springframework.test.web.client.RequestMatcher;

/**
 * 검사에서 쓸 좌표 봉투를 만든다.
 *
 * 열쇠 값을 여기 두어도 되는 이유는 이 열쇠로 감싼 것이 검사 프로세스 밖으로
 * 나가지 않기 때문이다. 다만 실제와 같은 32바이트 길이를 쓴다 — 길이가 다르면
 * 설정 검증에 걸려, 정작 보려던 감싸기 동작을 못 보게 된다.
 */
public final class TestLocationSeals {

    private TestLocationSeals() {
    }

    public static LocationSeal enabled() {
        LocationWireProperties properties = new LocationWireProperties();
        properties.setEnabled(true);
        properties.setKey(LocationSealTest.KEY);
        LocationSeal seal = new LocationSeal(properties, new ObjectMapper());
        seal.init();
        return seal;
    }

    /**
     * 보낸 요청에 좌표가 값 그대로 실리지 않았는지 본다.
     *
     * 감싸기가 어느 호출부에서 빠지면 그 경로만 조용히 예전처럼 평문으로
     * 나가는데, 응답은 똑같이 오므로 화면으로는 알 수 없다. 그래서 보내는
     * 자리마다 이 검사를 건다.
     */
    public static RequestMatcher coordinatesAreSealed() {
        return request -> {
            String query = request.getURI().getQuery();
            assertThat(query)
                    .as("좌표는 감싸서 보내야 한다")
                    .contains("loc=v1.")
                    .doesNotContain("lat=", "lng=");
        };
    }
}
