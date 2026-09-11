package map.service.user.policy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ServicePolicyEnforcementConfiguration {
    static final String PROTECTED_USER_ATTRIBUTE = "map.policy.protected-user";
    @Bean
    WebMvcConfigurer servicePolicyEnforcement(ServicePolicyService service) {
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new PolicyInterceptor(service)).addPathPatterns("/api/v1/**")
                        // Refusal must leave authentication, correction, deletion and safety reports accessible.
                        .excludePathPatterns("/api/v1/auth/**", "/api/v1/consents", "/api/v1/consents/**", "/api/v1/users/me",
                                "/api/v1/moderation/**");
            }
        };
    }
    static class PolicyInterceptor implements HandlerInterceptor {
        private final ServicePolicyService service;
        PolicyInterceptor(ServicePolicyService service) { this.service = service; }
        @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if ("OPTIONS".equals(request.getMethod())) return true;
            // Public invite preview contains no conversation content and cannot join/send.
            String path = request.getServletPath();
            if ("GET".equals(request.getMethod()) && path.matches("/api/v1/chat/invites/[^/]+")) return true;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long userId))
                throw new CustomException(ErrorCode.INVALID_TOKEN);
            service.requireEligible(userId);
            request.setAttribute(PROTECTED_USER_ATTRIBUTE, userId);
            return true;
        }
    }
}
