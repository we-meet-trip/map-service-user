package map.service.user.policy;

import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.jwt.JwtService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** A delayed success body must still be authorized when it reaches the HTTP serializer. */
@RestControllerAdvice
public class ServicePolicyResponseGuard implements ResponseBodyAdvice<Object> {
    private final ObjectProvider<ServicePolicyService> policy;
    private final ObjectProvider<JwtService> jwt;

    public ServicePolicyResponseGuard(ObjectProvider<ServicePolicyService> policy, ObjectProvider<JwtService> jwt) {
        this.policy = policy;
        this.jwt = jwt;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest input)
                || !(response instanceof ServletServerHttpResponse output)) return body;
        var servletRequest = input.getServletRequest();
        Object protectedUser = servletRequest.getAttribute(ServicePolicyEnforcementConfiguration.PROTECTED_USER_ATTRIBUTE);
        int status = output.getServletResponse().getStatus();
        if (!(protectedUser instanceof Long userId) || status < 200 || status >= 300) return body;
        try {
            String authorization = servletRequest.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer "))
                throw new CustomException(ErrorCode.INVALID_TOKEN);
            JwtService validator = jwt.getObject();
            Long currentUser = validator.extractUserId(validator.validateAccessToken(authorization.substring(7)));
            if (!userId.equals(currentUser)) throw new CustomException(ErrorCode.INVALID_TOKEN);
            policy.getObject().requireCurrentEligible(userId);
        } catch (CustomException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            // Neither the old generated body nor database/provider error details may escape.
            throw new CustomException(ErrorCode.SERVICE_POLICY_UNAVAILABLE);
        }
        return body;
    }
}
