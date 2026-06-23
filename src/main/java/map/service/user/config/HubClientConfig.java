package map.service.user.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HubClientConfig — hub 호출용 RestClient 빈 설정
 *
 * hub 게이트웨이의 동기 read 엔드포인트(/v1/weather)를 호출하기 위한 RestClient 를
 * 빈으로 등록한다(경계 B3). 구성은 AgentClientConfig 와 동일한 패턴이며, 프레임워크가
 * 자동 구성한 RestClient.Builder 를 주입받아 LocalDate ISO 직렬화 컨버터를 재사용한다.
 *
 * 내부 동작:
 * - 표준 라이브러리 HttpClient 를 HTTP/1.1 로 고정.
 * - connectTimeout 5초, readTimeout 은 hub.timeout-seconds(기본 5초).
 *
 * 사용처:
 * - HubWeatherClient 가 @Qualifier("hubRestClient") 로 본 빈을 주입받는다.
 */
@Configuration
public class HubClientConfig {

    /**
     * hub 호출용 RestClient 빈을 생성한다.
     *
     * @param builder        Spring 이 자동 구성한 RestClient.Builder
     * @param baseUrl        hub 서비스 base URL (hub.base-url, 기본 http://hub:8000)
     * @param timeoutSeconds 응답 읽기 타임아웃 초 (hub.timeout-seconds, 기본 5)
     * @return               base URL, HTTP/1.1, connect 5s / read N s 가 적용된 RestClient
     */
    @Bean(name = "hubRestClient")
    public RestClient hubRestClient(
            RestClient.Builder builder,
            @Value("${hub.base-url:http://hub:8000}") String baseUrl,
            @Value("${hub.timeout-seconds:5}") long timeoutSeconds
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
