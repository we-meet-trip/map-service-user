package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import map.service.user.global.exception.GlobalExceptionHandler;
import map.service.user.trip.TripGenerationException;

class RecommendationFailureTest {
    @Test void facadeUsesTypedFailureAndPreservesLegacyKeys() {
        var response = new GlobalExceptionHandler().handleTripGeneration(
                new TripGenerationException("no_matching_places", true));
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).containsEntry("error", "trip_generation_failed")
                .containsEntry("code", "no_matching_places").containsEntry("retryable", false);
    }

    @Test void missingUnknownOrUnsafeRetryValuesFailClosed() {
        assertThat(RecommendationFailure.of(null, true).code()).isEqualTo("generation_failed");
        assertThat(RecommendationFailure.of("private-provider-text", true).retryable()).isFalse();
        assertThat(RecommendationFailure.of("quota_exceeded", true).retryable()).isFalse();
        assertThat(RecommendationFailure.of("selection_invalid", true).httpStatus()).isEqualTo(502);
        assertThat(RecommendationFailure.of("upstream_unavailable", false).retryable()).isFalse();
        assertThat(RecommendationFailure.of("upstream_unavailable", true).retryable()).isTrue();
    }
}
