package map.service.user.recommend;

import java.nio.charset.StandardCharsets;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.RecommendRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * AgentClient — agent 서비스 호출 어댑터
 *
 * 외부 agent 서비스의 /v1/recommend 엔드포인트로 추천 작업 생성을 위임한다.
 * 본 클래스는 HTTP 호출과 오류 변환만 담당하며, 비즈니스 분기는 호출자(RecommendService)에 있다.
 *
 * client: @Qualifier("agentRestClient") 로 주입되는 RestClient.
 *         agent 호출용 baseUrl·타임아웃·HTTP/1.1 설정이 외부 Configuration 에서 구성되어 있다.
 */
@Component
public class AgentClient {

    private final RestClient client;

    /** 오류 응답 본문을 메모리에 읽을 최대 바이트(과대 응답 OOM 방지). */
    private static final int ERROR_BODY_MAX = 4096;

    public AgentClient(@Qualifier("agentRestClient") RestClient client) {
        this.client = client;
    }

    /**
     * agent 의 /v1/recommend 로 추천 작업 생성을 요청.
     *
     * Content-Type: application/json 으로 RecommendRequest 본문을 전송하고,
     * 응답이 4xx/5xx 이면 본문을 읽어 AgentRequestException 으로 변환해 던진다.
     * 정상 응답은 JobAccepted 로 역직렬화하여 반환한다.
     *
     * request: 클라이언트에서 검증된 RecommendRequest.
     */
    public JobAccepted requestRecommend(RecommendRequest request) {
        return client.post()
                .uri("/v1/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        (req, res) -> {
                            String body = readBody(res.getBody());
                            throw new AgentRequestException(
                                    res.getStatusCode().value(), body);
                        })
                .body(JobAccepted.class);
    }

    /**
     * 응답 본문 InputStream 을 UTF-8 문자열로 안전하게 변환.
     *
     * stream 이 null 이거나 IOException 이 발생하면 빈 문자열을 반환한다.
     * 오류 본문은 진단 요약 용도이므로 ERROR_BODY_MAX(4KB)까지만 읽어
     * 비정상적으로 큰 응답이 메모리에 무제한 버퍼링되는 것을 막는다.
     * try-with-resources 로 스트림을 닫는다.
     *
     * stream: 응답 본문 입력 스트림. null 가능.
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
