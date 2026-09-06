package map.service.user.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.stream.Stream;
import map.service.user.mobility.BikeStationException;
import map.service.user.mobility.PmVehicleException;
import map.service.user.places.PlacePhotosException;
import map.service.user.places.PlaceSearchException;
import map.service.user.places.ReviewSearchException;
import map.service.user.recommend.AgentRequestException;
import map.service.user.transit.SubwayRouteException;
import map.service.user.transit.TransitRouteException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

/** Real MVC exception resolution with synthetic upstream data, no network or database. */
class UpstreamErrorPrivacyTest {
    static final String RAW = "{\"api_key\":\"synthetic-provider-secret\","
            + "\"token\":\"synthetic-session-secret\",\"lat\":37.123456,\"lng\":127.654321}";

    static Stream<Arguments> upstreamFailures() {
        return Stream.of(
                Arguments.of(new AgentRequestException(422, RAW), "agent upstream error"),
                Arguments.of(new PlaceSearchException(422, RAW), "place_search_upstream_error"),
                Arguments.of(new ReviewSearchException(422, RAW), "review_search_upstream_error"),
                Arguments.of(new PlacePhotosException(422, RAW), "place_photos_upstream_error"),
                Arguments.of(new SubwayRouteException(422, RAW), "subway_route_upstream_error"),
                Arguments.of(new TransitRouteException(422, RAW), "transit_route_upstream_error"),
                Arguments.of(new BikeStationException(422, RAW), "bike_stations_upstream_error"),
                Arguments.of(new PmVehicleException(422, RAW), "pm_vehicles_upstream_error"));
    }

    @ParameterizedTest
    @MethodSource("upstreamFailures")
    void providerBodyNeverAppearsInHttpResponseExceptionMessageOrWarning(RuntimeException error, String code)
            throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start(); logger.addAppender(logs);
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new FailingController(error))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            String response = mvc.perform(get("/synthetic-upstream"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error").value(code))
                    .andExpect(jsonPath("$.upstream_status").value(422))
                    .andExpect(jsonPath("$.detail").isString())
                    .andReturn().getResponse().getContentAsString();
            assertPrivate(response); assertPrivate(error.getMessage());
            assertThat(logs.list).isNotEmpty();
            logs.list.forEach(log -> { assertPrivate(log.getFormattedMessage()); assertThat(log.getThrowableProxy()).isNull(); });
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void timeoutUrlAndCauseAreNotLoggedOrReturned() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start(); logger.addAppender(logs);
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new FailingController(new ResourceAccessException(RAW)))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            String response = mvc.perform(get("/synthetic-upstream"))
                    .andExpect(status().isGatewayTimeout()).andReturn().getResponse().getContentAsString();
            assertPrivate(response);
            assertThat(logs.list).isNotEmpty();
            logs.list.forEach(log -> assertPrivate(log.getFormattedMessage()));
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void asynchronousWorkerErrorIsPrivateAndLocalTimelineGuidanceIsPreserved() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start(); logger.addAppender(logs);
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new FailingController(
                    new map.service.user.trip.TripGenerationException(RAW)))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            String response = mvc.perform(get("/synthetic-upstream"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error").value("trip_generation_failed"))
                    .andReturn().getResponse().getContentAsString();
            assertPrivate(response);
            logs.list.forEach(log -> assertPrivate(log.getFormattedMessage()));
            var timeline = new map.service.user.trip.TripTimelineException();
            mvc = MockMvcBuilders.standaloneSetup(new FailingController(timeline))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            mvc.perform(get("/synthetic-upstream"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.message").value(timeline.getMessage()));
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    static void assertPrivate(String value) {
        assertThat(value).doesNotContain("synthetic-provider-secret", "synthetic-session-secret", "37.123456", "127.654321");
    }

    @RestController
    static class FailingController {
        private final RuntimeException error;
        FailingController(RuntimeException error) { this.error = error; }
        @GetMapping("/synthetic-upstream") public void fail() { throw error; }
    }
}
