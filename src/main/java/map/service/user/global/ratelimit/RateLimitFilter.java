package map.service.user.global.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.exception.ErrorResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, int[]> LIMITS = Map.of(
            "POST:/api/v1/auth/login",          new int[]{10, 60},
            "POST:/api/v1/auth/signup",         new int[]{5,  60},
            "POST:/api/v1/auth/token/refresh",  new int[]{20, 60},
            "POST:/api/v1/auth/kakao/callback", new int[]{10, 60}
    );

    private final RateLimitService rateLimitService;
    private final ObjectMapper     objectMapper;

    /**
     * 주소 헤더를 믿어도 되는 직전 발신자 대역.
     *
     * 엣지 프록시는 컨테이너 네트워크 안에서 오므로 사설 대역이 기본이다.
     * 다른 망 구성으로 바꾸면 이 값도 함께 좁혀야 헤더 신뢰 범위가 어긋나지 않는다.
     */
    private final List<IpAddressMatcher> trustedProxies;

    public RateLimitFilter(
            RateLimitService rateLimitService,
            ObjectMapper objectMapper,
            @Value("${ratelimit.trusted-proxies:172.16.0.0/12,10.0.0.0/8,192.168.0.0/16,127.0.0.0/8}")
            List<String> trustedProxyCidrs) {
        this.rateLimitService = rateLimitService;
        // 공유 빈을 직접 수정하지 않도록 copy 후 모듈 등록
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule());
        this.trustedProxies = trustedProxyCidrs.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .map(IpAddressMatcher::new)
                .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String routeKey = request.getMethod() + ":" + request.getRequestURI();
        int[] config = LIMITS.get(routeKey);

        if (config != null) {
            String ip  = extractClientIp(request);
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

    private void writeErrorResponse(HttpServletResponse response) throws IOException {
        ErrorResponse body = ErrorResponse.of(ErrorCode.RATE_LIMIT_EXCEEDED);
        response.setStatus(ErrorCode.RATE_LIMIT_EXCEEDED.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }

    /**
     * 한도 카운터의 키가 될 클라이언트 주소를 고른다.
     *
     * 프록시가 붙인 주소 헤더는 <b>직전 발신자가 신뢰 대역일 때만</b> 읽는다.
     * 헤더는 누구나 아무 값이나 넣어 보낼 수 있어서, 발신자를 보지 않고 헤더를
     * 믿으면 요청자가 카운터 키를 직접 정하게 된다. 그러면 값을 매 요청 바꿔가며
     * 한도를 무한히 우회할 수 있다.
     *
     * 헤더는 X-Real-IP 만 본다. 엣지가 이 헤더를 자기 판단값으로 항상 덮어쓰므로
     * 요청자가 보낸 값이 남지 않는다. X-Forwarded-For 는 값이 여러 개 이어질 수
     * 있어 어느 원소가 프록시가 쓴 것인지 헤더만으로는 가릴 수 없다.
     *
     * 신뢰 대역 밖에서 직접 들어온 요청은 헤더를 무시하고 실제 발신 주소를 쓴다.
     */
    private String extractClientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isTrustedProxy(remote)) {
            return remote;
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return remote;
    }

    /** 발신 주소가 주소 헤더를 믿어도 되는 프록시 대역에 속하는지. */
    private boolean isTrustedProxy(String remote) {
        if (remote == null || remote.isBlank()) {
            return false;
        }
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(remote)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // IP 형태가 아닌 발신 주소는 불일치로 본다.
            }
        }
        return false;
    }
}
