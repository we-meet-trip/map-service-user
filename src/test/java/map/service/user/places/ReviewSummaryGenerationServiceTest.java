package map.service.user.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.places.dto.ReviewSummaryRequest;
import map.service.user.places.dto.ReviewSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;

class ReviewSummaryGenerationServiceTest {
    private StringRedisTemplate redis;
    private ReviewSummaryService summaries;
    private ReviewSummaryGenerationService service;
    private final ReviewSummaryRequest request = new ReviewSummaryRequest("fixture", true,
            UUID.fromString("22222222-2222-2222-2222-222222222222"));

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        summaries = mock(ReviewSummaryService.class);
        service = new ReviewSummaryGenerationService(redis, summaries, new ObjectMapper());
    }

    @Test
    void missingAuthenticationOrConsentCannotContactRedisOrProvider() {
        assertThatThrownBy(() -> service.generate(null, request)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.generate(7L, new ReviewSummaryRequest("fixture", false,
                request.clientRequestId()))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(redis, summaries);
    }

    @Test
    void firstRequestGeneratesOnceAndReplayReturnsReceiptWithoutProvider() {
        AtomicReference<String> receipt = new AtomicReference<>();
        when(redis.execute(eq(ReviewSummaryGenerationService.CLAIM), anyList(), any(), any()))
                .thenAnswer(inv -> receipt.get() == null ? "START" : "EXISTING:" + receipt.get());
        when(redis.execute(eq(ReviewSummaryGenerationService.COMPLETE), anyList(), any(), any(), any()))
                .thenAnswer(inv -> { receipt.set(inv.getArgument(3)); return 1L; });
        ReviewSummaryResponse expected = new ReviewSummaryResponse("fixture", List.of("summary"), 1);
        when(summaries.summarize("fixture")).thenReturn(expected);

        assertThat(service.generate(7L, request)).isEqualTo(expected);
        assertThat(service.generate(7L, request)).isEqualTo(expected);
        verify(summaries, times(1)).summarize("fixture");
        assertThat(receipt.get()).doesNotContain("fixture", "22222222-2222", "userId");
        assertThatThrownBy(() -> service.generate(7L,
                new ReviewSummaryRequest("different", true, request.clientRequestId())))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REVIEW_SUMMARY_CONFLICT));
        verify(summaries, times(1)).summarize(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUSY", "LIMIT", "EXISTING:{\"fingerprint\":\"other\",\"complete\":false}",
            "EXISTING:broken", "UNKNOWN"})
    void protectionOrInvalidReceiptNeverCallsProvider(String claim) {
        when(redis.execute(eq(ReviewSummaryGenerationService.CLAIM), anyList(), any(), any()))
                .thenReturn(claim);
        assertThatThrownBy(() -> service.generate(7L, request)).isInstanceOf(CustomException.class);
        verifyNoInteractions(summaries);
    }

    @Test
    void cacheProtectionOutageFailsClosed() {
        when(redis.execute(eq(ReviewSummaryGenerationService.CLAIM), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("synthetic outage"));
        assertThatThrownBy(() -> service.generate(7L, request))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REVIEW_SUMMARY_UNAVAILABLE));
        verifyNoInteractions(summaries);
    }

    @Test
    void ambiguousCompletionNeverCallsProviderAgain() {
        AtomicReference<String> pending = new AtomicReference<>();
        when(redis.execute(eq(ReviewSummaryGenerationService.CLAIM), anyList(), any(), any()))
                .thenAnswer(inv -> {
                    if (pending.get() != null) return "EXISTING:" + pending.get();
                    pending.set(inv.getArgument(2));
                    return "START";
                });
        when(summaries.summarize("fixture"))
                .thenReturn(new ReviewSummaryResponse("fixture", List.of(), 0));
        when(redis.execute(eq(ReviewSummaryGenerationService.COMPLETE), anyList(), any(), any(), any()))
                .thenThrow(new IllegalStateException("synthetic lost acknowledgement"));
        assertThatThrownBy(() -> service.generate(7L, request)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.generate(7L, request)).isInstanceOf(CustomException.class);
        verify(summaries, times(1)).summarize("fixture");
    }
}
