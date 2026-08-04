package map.service.user.global.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import map.service.user.global.exception.CustomException;
import map.service.user.global.jwt.JwtService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX  = "Bearer ";

    /**
     * 토큰을 들고 왔는데 그 토큰이 거절됐음을 알리는 요청 속성 키.
     *
     * 인증을 강제하지 않는 설정에서는 여기서 거절해도 요청이 그대로 진행되므로,
     * 뒤쪽에서 보면 "토큰을 아예 안 보낸 요청"과 "보냈는데 만료된 요청"이 똑같이
     * 주인 없는 요청으로 보인다. 둘을 구분해야 하는 곳이 있어 표시를 남긴다.
     */
    public static final String REJECTED_TOKEN_ATTR = "map.jwt.rejected";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);

        if (token != null) {
            try {
                Claims claims = jwtService.validateAccessToken(token);
                Long userId = jwtService.extractUserId(claims);

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (CustomException e) {
                log.debug("JWT validation failed: {}", e.getMessage());
                // SecurityContext 비워둠 → 보호된 엔드포인트는 401로 응답.
                // 인증을 강제하지 않는 설정에서는 여기서 끊지 않고 통과시키되,
                // 소유자를 요구하는 쪽이 이 표시를 보고 되돌려 보낼 수 있게 한다.
                request.setAttribute(REJECTED_TOKEN_ATTR, e.getErrorCode());
            }
        }

        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
