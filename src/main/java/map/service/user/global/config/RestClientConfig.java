package map.service.user.global.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClientConfig — Kakao 호출용 RestClient 빈 설정
 *
 * Kakao OAuth 토큰 교환(kauth.kakao.com)과 사용자 정보 조회(kapi.kakao.com)를 호출하기 위한
 * RestClient 를 빈으로 등록한다(외부 경계). 두 호스트를 오가므로 base URL 은 두지 않고
 * 호출부에서 절대 URI 를 지정한다. HubClientConfig/AgentClientConfig 와 동일하게 표준
 * 라이브러리 HttpClient 로 connect/read 타임아웃을 고정하여 Kakao 지연 시 요청 스레드가
 * 무한 블록되지 않게 한다.
 */
@Configuration
public class RestClientConfig {

    /**
     * Kakao 호출용 RestClient 빈을 생성한다.
     *
     * @param builder            Spring 이 자동 구성한 RestClient.Builder
     * @param connectTimeoutMs   연결 타임아웃 ms (kakao.connect-timeout-ms, 기본 5000)
     * @param readTimeoutMs      응답 읽기 타임아웃 ms (kakao.read-timeout-ms, 기본 5000)
     * @return                   HTTP/1.1, connect/read 타임아웃이 적용된 RestClient (base URL 없음)
     */
    @Bean
    public RestClient kakaoRestClient(
            RestClient.Builder builder,
            @Value("${kakao.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${kakao.read-timeout-ms:5000}") long readTimeoutMs
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder
                .requestFactory(factory)
                .build();
    }
}
