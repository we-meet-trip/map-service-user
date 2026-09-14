package map.service.user.global.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.exception.ErrorResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, int[]> LIMITS = Map.of(
            "POST:/api/v1/auth/login",          new int[]{10, 60},
            "POST:/api/v1/auth/signup",         new int[]{5,  60},
            "POST:/api/v1/auth/token/refresh",  new int[]{20, 60},
            "POST:/api/v1/auth/kakao/callback", new int[]{10, 60},
            "GET:/api/v1/auth/apple/nonce", new int[]{10, 60},
            "POST:/api/v1/auth/apple/callback", new int[]{10, 60}
    );

    private final RateLimitService rateLimitService;
    private final ObjectMapper     objectMapper;
    private final ClientIpResolver clientIpResolver;

    public RateLimitFilter(
            RateLimitService rateLimitService,
            ObjectMapper objectMapper,
            ClientIpResolver clientIpResolver) {
        this.rateLimitService = rateLimitService;
        // 공유 빈을 직접 수정하지 않도록 copy 후 모듈 등록
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule());
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String routeKey = request.getMethod() + ":" + routePath(request);
        int[] config = LIMITS.get(routeKey);

        if (config != null) {
            String ip  = clientIpResolver.resolve(request);
            String key = routeKey + ":" + ip;
            int limit  = config[0];
            int windowSec = config[1];

            if (!rateLimitService.isAllowed(key, limit, Duration.ofSeconds(windowSec))) {
                writeErrorResponse(response);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * 요청 경로를 한 가지 모양으로 맞춘다.
     *
     * 원시 URI 를 그대로 쓰면 같은 처리기로 가는 요청이 다른 열쇠를 받는다.
     * 퍼센트 인코딩, 세미콜론 뒤 경로 파라미터, 이어진 슬래시가 모두 그 통로다.
     */
    private static String routePath(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request).replaceAll("/{2,}", "/");
    }

    private void writeErrorResponse(HttpServletResponse response) throws IOException {
        ErrorResponse body = ErrorResponse.of(ErrorCode.RATE_LIMIT_EXCEEDED);
        response.setStatus(ErrorCode.RATE_LIMIT_EXCEEDED.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
