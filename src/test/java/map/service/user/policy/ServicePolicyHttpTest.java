package map.service.user.policy;

import io.jsonwebtoken.Claims;
import map.service.user.global.config.*;
import map.service.user.global.exception.*;
import map.service.user.global.jwt.JwtService;
import map.service.user.global.ratelimit.*;
import map.service.user.global.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ServicePolicyController.class, AiConsentController.class, ServicePolicyHttpTest.ProtectedEndpoints.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RateLimitFilter.class,
        ClientIpResolver.class, CorsProperties.class, ServicePolicyEnforcementConfiguration.class,
        ServicePolicyHttpTest.ProtectedEndpoints.class})
class ServicePolicyHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean ServicePolicyService service;
    @MockitoBean AiConsentService ai;
    @MockitoBean JwtService jwt;
    @MockitoBean RateLimitService rates;
    @RestController static class ProtectedEndpoints {
        @PostMapping({"/api/v1/vision/permit", "/api/v1/chat/rooms/7/messages", "/api/v1/recommend/generate"})
        String serving() { return "unexpected serving"; }
        @GetMapping("/api/v1/users/me") String profile() { return "profile"; }
        @DeleteMapping("/api/v1/users/me") void deleteAccount() {}
    }
    private void authenticate() {
        Claims claims = mock(Claims.class);
        when(jwt.validateAccessToken("synthetic-token")).thenReturn(claims);
        when(jwt.extractUserId(claims)).thenReturn(7L);
    }
    @Test void anonymousConsentIs401EvenWithAuthEnforcementOff() throws Exception {
        mvc.perform(get("/api/v1/consents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/consents/ai")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/consents/ai/trip?expected_revision=1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service, ai);
    }
    @Test void unacceptedUserCannotReachVisionChatOrRecommendation() throws Exception {
        authenticate();
        doThrow(new CustomException(ErrorCode.SERVICE_POLICY_REQUIRED)).when(service).requireEligible(7L);
        for (String path : new String[]{"/api/v1/vision/permit", "/api/v1/chat/rooms/7/messages", "/api/v1/recommend/generate"})
            mvc.perform(post(path).header("Authorization", "Bearer synthetic-token"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SERVICE_POLICY_REQUIRED"));
    }
    @Test void acceptedUserCanServeButBirthdayCorrectionCanImmediatelyRevokeEligibility() throws Exception {
        authenticate();
        mvc.perform(post("/api/v1/vision/permit").header("Authorization", "Bearer synthetic-token"))
                .andExpect(status().isOk());
        doThrow(new CustomException(ErrorCode.AGE_RESTRICTED)).when(service).requireEligible(7L);
        mvc.perform(post("/api/v1/vision/permit").header("Authorization", "Bearer synthetic-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AGE_RESTRICTED"));
    }
    @Test void missingBirthdayIsForbiddenForRecommendationAndVisionEvenWithOldAcceptance() throws Exception {
        authenticate();
        doThrow(new CustomException(ErrorCode.AGE_INFORMATION_REQUIRED)).when(service).requireEligible(7L);
        for (String path : new String[]{"/api/v1/vision/permit", "/api/v1/chat/rooms/7/messages", "/api/v1/recommend/generate"})
            mvc.perform(post(path).header("Authorization", "Bearer synthetic-token"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AGE_INFORMATION_REQUIRED"));
    }
    @Test void missingBirthdayStatusAndConsentPostHaveAnExplicitContract() throws Exception {
        authenticate();
        when(service.status(7L)).thenReturn(new ServicePolicyService.Status("2026-09-07", "2026-09-07.1", 18, false, null, null));
        when(service.accept(eq(7L), any())).thenThrow(new CustomException(ErrorCode.AGE_INFORMATION_REQUIRED));
        mvc.perform(get("/api/v1/consents").header("Authorization", "Bearer synthetic-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.age_eligible").doesNotExist());
        mvc.perform(post("/api/v1/consents").header("Authorization", "Bearer synthetic-token")
                .contentType("application/json").content("{\"terms_version\":\"2026-09-07\",\"privacy_version\":\"2026-09-07.1\",\"is_18_or_older\":true,\"terms_accepted\":true,\"privacy_accepted\":true}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AGE_INFORMATION_REQUIRED"));
    }
    @Test void refusalStillAllowsStatusProfileAndDeletion() throws Exception {
        authenticate();
        when(service.status(7L)).thenReturn(new ServicePolicyService.Status("2026-09-07", "2026-09-07.1", 18, false, false, null));
        mvc.perform(get("/api/v1/consents").header("Authorization", "Bearer synthetic-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.minimum_age").value(18))
                .andExpect(jsonPath("$.age_eligible").value(false));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer synthetic-token")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/users/me").header("Authorization", "Bearer synthetic-token")).andExpect(status().isOk());
        verify(service, never()).requireEligible(any());
    }
    @Test void missingOrFalseConfirmationsAreRejectedBeforeServiceCall() throws Exception {
        authenticate();
        for (String body : new String[]{"{}", "{\"terms_version\":\"2026-09-07\",\"privacy_version\":\"2026-09-07.1\",\"is_18_or_older\":true,\"terms_accepted\":false,\"privacy_accepted\":true}"})
            mvc.perform(post("/api/v1/consents").header("Authorization", "Bearer synthetic-token")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verify(service, never()).accept(any(), any());
    }
    @Test void explicitConsentUsesAuthenticatedIdentity() throws Exception {
        authenticate();
        when(service.accept(eq(7L), any())).thenReturn(new ServicePolicyService.Status("2026-09-07", "2026-09-07.1", 18, true, true, null));
        mvc.perform(post("/api/v1/consents").header("Authorization", "Bearer synthetic-token")
                .contentType("application/json").content("{\"terms_version\":\"2026-09-07\",\"privacy_version\":\"2026-09-07.1\",\"is_18_or_older\":true,\"terms_accepted\":true,\"privacy_accepted\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        verify(service).accept(eq(7L), any());
    }
    @Test void optionalWithdrawalUsesAuthenticatedIdentityAndRemainsAvailableWithoutServicePolicy() throws Exception {
        authenticate();
        when(ai.revoke(7L,"trip",1)).thenReturn(new AiConsentService.Status("trip",AiConsentService.VERSION,false,false,2,null));
        mvc.perform(delete("/api/v1/consents/ai/trip?expected_revision=1").header("Authorization","Bearer synthetic-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(false)).andExpect(jsonPath("$.revision").value(2));
        verify(ai).revoke(7L,"trip",1);
        verify(service,never()).requireEligible(any());
    }

}
