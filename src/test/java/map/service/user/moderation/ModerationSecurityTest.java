package map.service.user.moderation;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.jsonwebtoken.Claims;
import java.util.List;
import map.service.user.global.config.*;
import map.service.user.global.jwt.JwtService;
import map.service.user.global.ratelimit.*;
import map.service.user.global.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers={ModerationController.class,InternalModerationController.class},properties={"auth.enforced=false","internal.admin.token=synthetic-internal-token","internal.admin.trusted-cidrs=10.0.0.0/8"})
@Import({InternalAdminSecurityConfig.class,SecurityConfig.class,JwtAuthenticationFilter.class,RateLimitFilter.class,ClientIpResolver.class,CorsProperties.class})
@org.springframework.boot.context.properties.EnableConfigurationProperties(InternalAdminProperties.class)
class ModerationSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtService jwt;
    @MockitoBean RateLimitService rate;
    @MockitoBean ModerationService service;
    @Test void internalQueueRequiresBothNetworkAndTokenAndActionsRequireOpaqueActor() throws Exception {
        when(service.queue(ModerationReport.Status.OPEN,50)).thenReturn(List.of());
        String path="/internal/admin/moderation/reports";
        mvc.perform(get(path)).andExpect(status().isForbidden());
        mvc.perform(get(path).header("X-Internal-Token","synthetic-internal-token")).andExpect(status().isForbidden());
        mvc.perform(get(path).with(req->{req.setRemoteAddr("10.1.1.1"); return req;})).andExpect(status().isForbidden());
        mvc.perform(get(path).with(req->{req.setRemoteAddr("10.1.1.1"); return req;})
                .header("X-Internal-Token","synthetic-internal-token")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(post(path+"/00000000-0000-4000-8000-000000000001/actions")
                .with(req->{req.setRemoteAddr("10.1.1.1"); return req;}).header("X-Internal-Token","synthetic-internal-token")
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        verify(service,never()).act(any(),any(),any());
    }
    @Test void everyUserModerationRouteRequiresAuthenticationEvenWhenEnforcementFlagIsFalse() throws Exception {
        mvc.perform(get("/api/v1/moderation/reports")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/moderation/blocks")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/moderation/reports").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/moderation/blocks/17")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/moderation/blocks/17")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void jwtSubjectControlsReportsAndBlocksWithoutBodyIdentityOverride() throws Exception {
        Claims claims=mock(Claims.class); when(jwt.validateAccessToken("synthetic-token")).thenReturn(claims);
        when(jwt.extractUserId(claims)).thenReturn(7L);
        when(service.ownReports(7L)).thenReturn(List.of()); when(service.ownBlocks(7L)).thenReturn(List.of());
        mvc.perform(get("/api/v1/moderation/reports").header("Authorization","Bearer synthetic-token"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/moderation/blocks").header("Authorization","Bearer synthetic-token"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(put("/api/v1/moderation/blocks/17").header("Authorization","Bearer synthetic-token"))
                .andExpect(status().isNoContent());
        verify(service).block(7L,17L);
        mvc.perform(post("/api/v1/moderation/reports").header("Authorization","Bearer synthetic-token")
                .contentType("application/json").content("{\"client_request_id\":\"00000000-0000-4000-8000-000000000001\",\"content_type\":\"VISION\",\"reason\":\"OTHER\",\"description\":\"synthetic\",\"user_id\":17}"))
                .andExpect(status().isBadRequest());
        verify(service,never()).submit(any(),any());
    }
}
