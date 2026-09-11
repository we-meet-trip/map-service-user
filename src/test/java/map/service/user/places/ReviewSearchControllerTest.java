package map.service.user.places;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.security.JwtAuthenticationFilter;
import map.service.user.places.dto.ReviewItem;
import map.service.user.places.dto.ReviewSearchResponse;
import map.service.user.places.dto.ReviewSummaryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
    @MockitoBean private ReviewSummaryGenerationService generationService;
    // @WebMvcTest 는 서블릿 Filter 빈(JWT/RateLimit)을 컨텍스트에 포함하므로,
    // 실제 의존성(JwtService/RateLimitService) 없이 로드되도록 필터를 모킹한다.
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter rateLimitFilter;

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

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

    @Test
    @DisplayName("요약 조회 — 200 OK 및 두 줄·근거 수 반환")
    void summary_valid_returns200() throws Exception {
        when(summaryService.cachedSummary(eq("속초해변")))
                .thenReturn(new ReviewSummaryResponse(
                        "속초해변", List.of("첫 줄", "둘째 줄"), 7));

        mockMvc.perform(get("/api/v1/reviews/summary").param("query", "속초해변"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("속초해변"))
                .andExpect(jsonPath("$.bullets[0]").value("첫 줄"))
                .andExpect(jsonPath("$.bullets[1]").value("둘째 줄"))
                .andExpect(jsonPath("$.sourceCount").value(7));
        verify(summaryService, never()).summarize(any());
        verifyNoInteractions(generationService);
    }

    @Test
    @DisplayName("요약 조회 — 근거가 없으면 빈 목록(오류가 아님)")
    void summary_noSources_returnsEmpty() throws Exception {
        when(summaryService.cachedSummary(eq("무명장소")))
                .thenReturn(new ReviewSummaryResponse("무명장소", List.of(), 0));

        mockMvc.perform(get("/api/v1/reviews/summary").param("query", "무명장소"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bullets").isEmpty())
                .andExpect(jsonPath("$.sourceCount").value(0));
    }

    @Test
    @DisplayName("요약 조회 — query 공백이면 400")
    void summary_blankQuery_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/summary").param("query", "  "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("요약 조회 — query 길이 상한 초과면 400")
    void summary_tooLongQuery_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/summary")
                        .param("query", "가".repeat(61)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void explicitPostUsesAuthenticatedUserAndReturnsSummary() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, List.of()));
        when(generationService.generate(eq(7L), any()))
                .thenReturn(new ReviewSummaryResponse("fixture", List.of("summary"), 1));
        mockMvc.perform(post("/api/v1/reviews/summary").contentType("application/json")
                        .content("{\"query\":\"fixture\",\"consent\":true,"
                                + "\"client_request_id\":\"22222222-2222-2222-2222-222222222222\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bullets[0]").value("summary"));
        verify(generationService).generate(eq(7L), any());
        verifyNoInteractions(summaryService, client);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"query\":\"fixture\",\"client_request_id\":\"22222222-2222-2222-2222-222222222222\"}",
            "{\"query\":\"fixture\",\"consent\":false,\"client_request_id\":\"22222222-2222-2222-2222-222222222222\"}",
            "{\"query\":\"fixture\",\"consent\":true}",
            "{\"query\":\"fixture\",\"consent\":true,\"client_request_id\":\"not-a-uuid\"}",
            "{\"query\":\" \",\"consent\":true,\"client_request_id\":\"22222222-2222-2222-2222-222222222222\"}"
    })
    void postRejectsImplicitConsentOrInvalidRequest(String body) throws Exception {
        mockMvc.perform(post("/api/v1/reviews/summary").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(generationService, summaryService, client);
    }
}
