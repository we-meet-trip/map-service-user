package map.service.user.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * AgentClientConfig — agent 호출용 RestClient 빈 설정
 *
 * agent 서비스의 추천 엔드포인트를 호출하기 위한 RestClient 를 빈으로 등록한다.
 * 프레임워크가 자동 구성한 RestClient.Builder 를 주입받아 사용함으로써
 * Spring 이 등록한 HttpMessageConverter (LocalDate / LocalTime ISO 직렬화 포함)를
 * 그대로 재사용한다.
 *
 * 내부 동작:
 * - 표준 라이브러리의 HttpClient 를 HTTP/1.1 로 고정한 뒤
 *   JdkClientHttpRequestFactory 에 주입하여 RestClient 의 요청 팩토리로 사용한다.
 * - connectTimeout 은 5초로 고정.
 * - readTimeout 은 agent.timeout-seconds 프로퍼티 값(기본 60초) 으로 설정.
 *
 * 사용처:
 * - AgentClient 가 Qualifier("agentRestClient") 로 본 빈을 주입받아 사용한다.
 */
@Configuration
public class AgentClientConfig {

    /**
     * agent 호출용 RestClient 빈을 생성한다.
     *
     * @param builder         Spring 이 자동 구성한 RestClient.Builder
     * @param baseUrl         agent 서비스 base URL (agent.base-url 프로퍼티, 기본 http://agent:8000)
     * @param timeoutSeconds  응답 읽기 타임아웃 초 (agent.timeout-seconds 프로퍼티, 기본 60)
     * @return                base URL, HTTP/1.1, connect 5s / read N s 가 적용된 RestClient
     */
    @Bean(name = "agentRestClient")
    public RestClient agentRestClient(
            RestClient.Builder builder,
            @Value("${agent.base-url:http://agent:8000}") String baseUrl,
            @Value("${agent.timeout-seconds:60}") long timeoutSeconds
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
