package map.service.user.policy;

import io.jsonwebtoken.Claims;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import map.service.user.global.config.*;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.jwt.JwtService;
import map.service.user.global.ratelimit.*;
import map.service.user.global.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ServicePolicyInFlightHttpTest.DelayedEndpoints.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RateLimitFilter.class, ClientIpResolver.class,
        CorsProperties.class, ServicePolicyEnforcementConfiguration.class,
        ServicePolicyInFlightHttpTest.DelayedEndpoints.class})
class ServicePolicyInFlightHttpTest {
    @Autowired MockMvc mvc;
    @Autowired DelayedEndpoints endpoints;
    @MockitoBean ServicePolicyService policy;
    @MockitoBean JwtService jwt;
    @MockitoBean RateLimitService rates;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<ErrorCode> changedPolicy = new AtomicReference<>();
    private final AtomicReference<ErrorCode> changedToken = new AtomicReference<>();

    @RestController static class DelayedEndpoints {
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch complete = new CountDownLatch(1);
        void awaitResult() throws InterruptedException {
            entered.countDown();
            if (!complete.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic result timeout");
        }
        void reset() { entered = new CountDownLatch(1); complete = new CountDownLatch(1); }
        @PostMapping({"/api/v1/trip/generate", "/api/v1/trip/research", "/api/v1/trip/replan",
                "/api/v1/reviews/summary", "/api/v1/recommend/fixture/research", "/api/v1/vision/permit"})
        Map<String,String> generate() throws InterruptedException { awaitResult(); return Map.of("result", "SYNTHETIC_PROTECTED_RESULT"); }
        @GetMapping("/api/v1/recommend/fixture")
        ResponseEntity<String> poll() throws InterruptedException {
            awaitResult(); return ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body("{\"result\":\"SYNTHETIC_PROTECTED_RESULT\"}");
        }
        @GetMapping("/api/v1/users/me") Map<String,Boolean> profile() { return Map.of("can_correct_profile", true); }
        @PostMapping("/api/v1/trip/error") ResponseEntity<Map<String,String>> providerError() {
            return ResponseEntity.status(502).body(Map.of("code", "SYNTHETIC_UPSTREAM_FAILURE"));
        }
    }
    @BeforeEach void setup() {
        Claims claims = mock(Claims.class);
        when(jwt.validateAccessToken("synthetic-session")).thenAnswer(invocation -> {
            if (changedToken.get() != null) throw new CustomException(changedToken.get());
            return claims;
        });
        when(jwt.extractUserId(claims)).thenReturn(7L);
        doAnswer(invocation -> {
            if (changedPolicy.get() != null) throw new CustomException(changedPolicy.get());
            return null;
        }).when(policy).requireCurrentEligible(7L);
    }
    @AfterEach void cleanup() { endpoints.complete.countDown(); executor.shutdownNow(); }

    private MvcResult delayed(String path, boolean polling, Runnable change) throws Exception {
        endpoints.reset();
        Future<MvcResult> response = executor.submit(() -> mvc.perform((polling ? get(path) : post(path))
                .header("Authorization", "Bearer synthetic-session")).andReturn());
        assertThat(endpoints.entered.await(3, TimeUnit.SECONDS)).isTrue();
        change.run();
        endpoints.complete.countDown();
        return response.get(5, TimeUnit.SECONDS);
    }

    @Test void birthdayCorrectionDuringEachGenerativeResponseRemovesTheGeneratedBody() throws Exception {
        for (String path : new String[]{"/api/v1/trip/generate", "/api/v1/trip/research", "/api/v1/trip/replan",
                "/api/v1/reviews/summary", "/api/v1/recommend/fixture/research", "/api/v1/vision/permit"}) {
            changedPolicy.set(null);
            var result = delayed(path, false, () -> changedPolicy.set(ErrorCode.AGE_RESTRICTED));
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString()).contains("AGE_RESTRICTED")
                    .doesNotContain("SYNTHETIC_PROTECTED_RESULT");
        }
    }
    @Test void completedPollDoesNotReleaseAResultAfterMissingBirthdayOrPolicyChange() throws Exception {
        for (ErrorCode code : new ErrorCode[]{ErrorCode.AGE_INFORMATION_REQUIRED, ErrorCode.SERVICE_POLICY_REQUIRED}) {
            changedPolicy.set(null);
            var result = delayed("/api/v1/recommend/fixture", true, () -> changedPolicy.set(code));
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString()).contains(code.getCode()).doesNotContain("SYNTHETIC_PROTECTED_RESULT");
        }
    }
    @Test void expiredOrLoggedOutSessionCannotReceiveAlreadyComputedContent() throws Exception {
        for (ErrorCode code : new ErrorCode[]{ErrorCode.EXPIRED_TOKEN, ErrorCode.BLACKLISTED_TOKEN}) {
            changedToken.set(null);
            var result = delayed("/api/v1/trip/generate", false, () -> changedToken.set(code));
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("SYNTHETIC_PROTECTED_RESULT");
        }
    }
    @Test void failedLatestPermissionLookupFailsClosedWithNoGeneratedBody() throws Exception {
        var result = delayed("/api/v1/trip/generate", false,
                () -> doThrow(new IllegalStateException("synthetic database unavailable")).when(policy).requireCurrentEligible(7L));
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentAsString()).contains("SERVICE_POLICY_UNAVAILABLE")
                .doesNotContain("SYNTHETIC_PROTECTED_RESULT", "synthetic database unavailable");
    }
    @Test void eligibleUnchangedSessionStillReceivesTheRealBody() throws Exception {
        var result = delayed("/api/v1/trip/generate", false, () -> {});
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("SYNTHETIC_PROTECTED_RESULT");
        verify(policy).requireCurrentEligible(7L);
    }
    @Test void profileCorrectionAndOriginalFailureBodiesDoNotReenterServingGuard() throws Exception {
        changedPolicy.set(ErrorCode.AGE_RESTRICTED);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer synthetic-session"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.can_correct_profile").value(true));
        mvc.perform(post("/api/v1/trip/error").header("Authorization", "Bearer synthetic-session"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("SYNTHETIC_UPSTREAM_FAILURE"));
        verify(policy, never()).requireCurrentEligible(any());
    }
}
