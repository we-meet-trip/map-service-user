package map.service.user.policy;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import map.service.user.global.config.*;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.jwt.JwtService;
import map.service.user.global.ratelimit.*;
import map.service.user.global.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@WebMvcTest(controllers = AiConsentInFlightHttpTest.DelayedEndpoints.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RateLimitFilter.class, ClientIpResolver.class,
        CorsProperties.class, ServicePolicyEnforcementConfiguration.class, AiConsentInFlightHttpTest.DelayedEndpoints.class})
class AiConsentInFlightHttpTest {
    @Autowired MockMvc mvc;
    @Autowired DelayedEndpoints endpoint;
    @MockitoBean ServicePolicyService policy;
    @MockitoBean AiConsentService ai;
    @MockitoBean JwtService jwt;
    @MockitoBean RateLimitService rates;
    final AtomicReference<ErrorCode> denied = new AtomicReference<>();
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    @RestController static class DelayedEndpoints {
        CountDownLatch entered = new CountDownLatch(1), complete = new CountDownLatch(1);
        @PostMapping("/api/v1/trip/generate") Map<String,String> generated(HttpServletRequest request) throws InterruptedException {
            request.setAttribute(AiConsentService.REQUEST_PERMITS,
                    Set.of(new AiConsentService.Permit(7L,"trip",1,false,"synthetic-session")));
            entered.countDown();
            if (!complete.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("synthetic timeout");
            return Map.of("result","SYNTHETIC_PROTECTED_RESULT");
        }
    }
    @BeforeEach void setup() {
        Claims claims = mock(Claims.class);
        when(jwt.validateAccessToken("synthetic-session")).thenReturn(claims);
        when(jwt.extractUserId(claims)).thenReturn(7L);
        doAnswer(inv -> { if (denied.get()!=null) throw new CustomException(denied.get()); return null; })
                .when(ai).requireCurrent(any());
    }
    @AfterEach void cleanup() { endpoint.complete.countDown(); executor.shutdownNow(); }
    @Test void withdrawalRegrantAndStorageOutageNeverSerializeGeneratedContent() throws Exception {
        for (ErrorCode failure : new ErrorCode[]{ErrorCode.AI_CONSENT_REQUIRED, ErrorCode.AI_CONSENT_CHANGED,
                ErrorCode.AI_CONSENT_UNAVAILABLE}) {
            denied.set(null); endpoint.entered = new CountDownLatch(1); endpoint.complete = new CountDownLatch(1);
            Future<MvcResult> pending = executor.submit(() -> mvc.perform(post("/api/v1/trip/generate")
                    .header("Authorization","Bearer synthetic-session")).andReturn());
            assertThat(endpoint.entered.await(3,TimeUnit.SECONDS)).isTrue();
            denied.set(failure); endpoint.complete.countDown();
            var response=pending.get(5,TimeUnit.SECONDS).getResponse();
            assertThat(response.getStatus()).isEqualTo(failure.getHttpStatus().value());
            assertThat(response.getContentAsString()).contains(failure.getCode()).doesNotContain("SYNTHETIC_PROTECTED_RESULT");
        }
    }
}
