package map.service.user.places;

import java.util.List;
import map.service.user.places.dto.ReviewItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * AgentSummaryClient — agent /v1/reviews/summary 호출 어댑터 (경계 B2)
 *
 * 장소 한 곳의 블로그 후기를 두 줄로 요약받는다. 추천 작업 생성과 같은
 * RestClient 빈을 재사용하므로 base URL·타임아웃·내부 토큰 헤더가 이미
 * 구성돼 있다.
 *
 * 요약은 화면의 보조 정보라 실패를 흡수한다. 한도에 걸리거나 모델이 답을
 * 못 만들면 빈 목록을 돌려주고, 호출 측은 요약 영역만 접은 채 나머지를
 * 그대로 보여준다.
 *
 * client: @Qualifier("agentRestClient") RestClient.
 */
@Component
public class AgentSummaryClient {

    private static final Logger log =
            LoggerFactory.getLogger(AgentSummaryClient.class);

    /** 한 번에 근거로 넘길 후기 수 상한. agent 요청 스키마의 상한과 같다. */
    private static final int MAX_SNIPPETS = 7;

    /**
     * 제목·본문 길이 상한. 받는 쪽 스키마와 같은 값으로 잘라 보낸다.
     *
     * 자르지 않으면 긴 글 한 건 때문에 요청 전체가 검증에 걸려, 그 장소는
     * 요약이 영영 비고 왕복만 반복된다. 외부에서 온 글 길이는 우리가 정할 수
     * 없으므로 보내는 쪽에서 맞춘다.
     */
    private static final int MAX_TITLE = 200;
    private static final int MAX_DESCRIPTION = 500;

    private final RestClient client;

    public AgentSummaryClient(
            @Qualifier("agentRestClient") RestClient client
    ) {
        this.client = client;
    }

    /**
     * 후기 묶음을 근거로 두 줄 요약을 받는다.
     *
     * placeName: 요약 대상 장소명.
     * reviews: 이미 조회해 둔 후기. 여기서 다시 조회하지 않는다 — 화면에
     *          보이는 목록과 요약의 근거를 같게 유지하기 위함이다.
     *
     * 반환: 두 줄 요약. 근거가 없거나 호출이 실패하면 빈 목록.
     */
    public List<String> summarize(String placeName, List<ReviewItem> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return List.of();
        }
        List<Snippet> snippets = reviews.stream()
                .filter(r -> r.description() != null
                        && !r.description().isBlank())
                .limit(MAX_SNIPPETS)
                .map(r -> new Snippet(
                        clamp(r.title(), MAX_TITLE),
                        clamp(r.description(), MAX_DESCRIPTION)))
                .toList();
        if (snippets.isEmpty()) {
            return List.of();
        }
        try {
            Response res = client.post()
                    .uri("/v1/reviews/summary")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(placeName, snippets))
                    .retrieve()
                    .body(Response.class);
            if (res == null || res.bullets() == null) {
                return List.of();
            }
            return res.bullets();
        } catch (RuntimeException e) {
            // 한도 초과(429)·모델 실패(502)·타임아웃 전부 여기로 온다.
            // 요약이 없다고 장소 상세를 못 그리는 것은 아니므로 흡수한다.
            log.warn("agent reviews summary failed reason={}", e.getMessage());
            return List.of();
        }
    }

    /** 길이 상한에 맞춰 자른다. null 은 빈 문자열로 접는다. */
    private static String clamp(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 요청 본문 — agent 의 요약 요청 스키마와 1:1. */
    private record Request(String place_name, List<Snippet> reviews) {
    }

    /** 요청에 실리는 후기 한 건. */
    private record Snippet(String title, String description) {
    }

    /** 응답 본문 — 두 줄 요약. */
    private record Response(List<String> bullets) {
    }
}
