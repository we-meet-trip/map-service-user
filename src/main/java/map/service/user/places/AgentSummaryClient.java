package map.service.user.places;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * 한 번에 요약을 맡길 장소 수 상한. agent 요청 스키마의 상한과 같다.
     *
     * 넘겨받은 목록이 더 길면 앞에서부터 이 수만큼만 보낸다 — 그대로 보내면
     * 요청 전체가 검증에 걸려 한 건도 요약을 못 받는다.
     */
    private static final int MAX_PLACES = 7;

    /**
     * 제목·본문 길이 상한. 받는 쪽 스키마와 같은 값으로 잘라 보낸다.
     *
     * 자르지 않으면 긴 글 한 건 때문에 요청 전체가 검증에 걸려, 그 장소는
     * 요약이 영영 비고 왕복만 반복된다. 외부에서 온 글 길이는 우리가 정할 수
     * 없으므로 보내는 쪽에서 맞춘다.
     */
    private static final int MAX_TITLE = 200;
    private static final int MAX_DESCRIPTION = 500;
    private static final int MAX_CATEGORY = 80;
    private static final int MAX_PLACE_NAME = 80;

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
        List<Snippet> snippets = toSnippets(reviews);
        if (snippets.isEmpty()) {
            return List.of();
        }
        try {
            Response res = client.post()
                    .uri("/v1/reviews/summary")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(clamp(placeName, MAX_PLACE_NAME), null, snippets))
                    .retrieve()
                    .body(Response.class);
            if (res == null || res.bullets() == null) {
                return List.of();
            }
            return res.bullets();
        } catch (RuntimeException e) {
            // 한도 초과(429)·모델 실패(502)·타임아웃 전부 여기로 온다.
            // 요약이 없다고 장소 상세를 못 그리는 것은 아니므로 흡수한다.
            log.warn("agent reviews summary failed reason={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 여러 장소를 한 번에 요약받는다.
     *
     * 장소마다 따로 부르면 호출 수가 장소 수만큼 늘어 모델의 분당 상한에
     * 걸린다. 한 번에 묶어 보내 일정 하나를 호출 1회로 처리한다.
     *
     * places: 요약 대상. 스키마 상한을 넘는 뒷부분은 보내지 않는다 —
     *         넘겨 보내면 요청 전체가 검증에 걸려 한 건도 못 받는다.
     *
     * 반환: **호출자가 넘긴 목록에서의 위치** → 두 줄. 근거가 없는 장소는
     *      키가 없다.
     */
    public Map<Integer, List<String>> summarizeBatch(List<BatchPlace> places) {
        if (places == null || places.isEmpty()) {
            return Map.of();
        }
        // 근거가 없는 장소는 보내지 않으므로, 보낸 목록의 순번과 호출자
        // 목록의 위치가 어긋난다. 원래 위치를 따로 들고 응답을 되짚는다 —
        // 이걸 놓치면 A 장소의 요약이 B 장소 것으로 저장된다.
        List<Integer> callerIndex = new ArrayList<>();
        List<BatchRequestPlace> sent = new ArrayList<>();
        for (int i = 0; i < places.size() && sent.size() < MAX_PLACES; i++) {
            BatchPlace place = places.get(i);
            List<Snippet> snippets = toSnippets(place.reviews());
            if (snippets.isEmpty()) {
                continue;
            }
            callerIndex.add(i);
            sent.add(new BatchRequestPlace(
                    clamp(place.name(), MAX_PLACE_NAME),
                    clampOrNull(place.category()), snippets));
        }
        if (sent.isEmpty()) {
            return Map.of();
        }
        try {
            BatchResponse res = client.post()
                    .uri("/v1/reviews/summary/batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new BatchRequest(sent))
                    .retrieve()
                    .body(BatchResponse.class);
            if (res == null || res.results() == null) {
                return Map.of();
            }
            Map<Integer, List<String>> out = new LinkedHashMap<>();
            for (BatchResult r : res.results()) {
                if (r == null || r.bullets() == null || r.bullets().isEmpty()) {
                    continue;
                }
                Integer index = r.index();
                if (index == null || index < 0 || index >= callerIndex.size()) {
                    continue;
                }
                out.put(callerIndex.get(index), r.bullets());
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("agent reviews batch summary failed reason={}",
                    e.getClass().getSimpleName());
            return Map.of();
        }
    }

    /** 후기를 요청에 실을 형태로 자르고 거른다. 본문 없는 건은 버린다. */
    private static List<Snippet> toSnippets(List<ReviewItem> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return List.of();
        }
        return reviews.stream()
                .filter(r -> r != null && r.description() != null
                        && !r.description().isBlank())
                .limit(MAX_SNIPPETS)
                .map(r -> new Snippet(
                        clamp(r.title(), MAX_TITLE),
                        clamp(r.description(), MAX_DESCRIPTION)))
                .toList();
    }

    /** 길이 상한에 맞춰 자른다. null 은 빈 문자열로 접는다. */
    private static String clamp(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 선택 항목용 — null 은 null 로 두고, 값이 있으면 상한에 맞춰 자른다. */
    private static String clampOrNull(String value) {
        return value == null ? null : clamp(value, MAX_CATEGORY);
    }

    /** 배치 요청에 실을 장소 한 건 — 호출 측이 채워 넣는 입력. */
    public record BatchPlace(
            String name, String category, List<ReviewItem> reviews) {
    }

    /** 요청 본문 — agent 의 단건 요약 요청 스키마와 1:1. */
    private record Request(
            String place_name, String category, List<Snippet> reviews) {
    }

    /** 요청에 실리는 후기 한 건. */
    private record Snippet(String title, String description) {
    }

    /** 응답 본문 — 두 줄 요약. */
    private record Response(List<String> bullets) {
    }

    /** 배치 요청 본문. */
    private record BatchRequest(List<BatchRequestPlace> places) {
    }

    /** 배치 요청에 실리는 장소 한 건 — agent 스키마와 1:1. */
    private record BatchRequestPlace(
            String place_name, String category, List<Snippet> reviews) {
    }

    /** 배치 응답 본문. */
    private record BatchResponse(List<BatchResult> results) {
    }

    /** 배치 응답의 장소 1건. index 는 **보낸 목록** 기준이다. */
    private record BatchResult(Integer index, List<String> bullets) {
    }
}
