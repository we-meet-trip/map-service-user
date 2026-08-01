package map.service.user.places;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.security.JwtAuthenticationFilter;
import map.service.user.places.dto.ReviewItem;
import map.service.user.places.dto.ReviewSearchResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ReviewSearchControllerTest — 리뷰 검색 엔드포인트 단위 테스트 (MockMvc)
 *
 * 검증: 정상 조회 200 + 본문, query 공백/ display 범위 위반 시 400.
 */
@WebMvcTest(ReviewSearchController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ReviewSearchController 단위 테스트 (MockMvc)")
class ReviewSearchControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ReviewSearchClient client;
    @MockitoBean private ReviewSummaryService summaryService;
    // @WebMvcTest 는 서블릿 Filter 빈(JWT/RateLimit)을 컨텍스트에 포함하므로,
    // 실제 의존성(JwtService/RateLimitService) 없이 로드되도록 필터를 모킹한다.
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter rateLimitFilter;

    @Test
    @DisplayName("정상 조회 — 200 OK 및 리뷰 본문 반환")
    void search_valid_returns200() throws Exception {
        ReviewSearchResponse response = new ReviewSearchResponse(
                "cafe",
                List.of(new ReviewItem("제목", "설명", "블로거", "20260101", "http://x/1")),
                1, 1);
        when(client.search(eq("cafe"), any(), any(), any()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/reviews").param("query", "cafe").param("display", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("cafe"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.reviews[0].title").value("제목"));
    }

    @Test
    @DisplayName("query 공백 — 400 Bad Request")
    void search_blankQuery_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/reviews").param("query", "  "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("display 범위 초과 — 400 Bad Request")
    void search_displayOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/reviews").param("query", "cafe").param("display", "20"))
                .andExpect(status().isBadRequest());
    }
}
