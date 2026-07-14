package map.service.user.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import map.service.user.global.config.InternalAdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * InternalAdminGuardFilter — /internal/admin/** 접근 가드
 *
 * hub 의 internal_guard(CIDR 화이트리스트 + X-Internal-Token 상수시간 비교)를
 * Spring 필터로 이식한 것이다. 두 조건을 모두 만족해야 다음 필터/컨트롤러로
 * 진행하며, 하나라도 실패하면 403 을 직접 기록하고 체인을 끊는다.
 *
 *   1) request.getRemoteAddr() 이 신뢰 CIDR 에 속하는가
 *   2) 헤더 X-Internal-Token 이 설정 토큰과 정확히 일치하는가(상수시간)
 *
 * 본 필터는 스프링 빈이 아니며(전역 서블릿 자동등록 방지), InternalAdminSecurityConfig
 * 가 /internal/** 전용 SecurityFilterChain 에만 수동으로 끼운다. 그 외 요청 경로에는
 * 적용되지 않는다.
 *
 * fail-closed: 설정 토큰이 비어 있거나 헤더가 없으면 일치할 수 없어 거부된다.
 */
public class InternalAdminGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalAdminGuardFilter.class);
    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final List<IpAddressMatcher> trustedMatchers;
    private final byte[] expectedToken;

    public InternalAdminGuardFilter(InternalAdminProperties props) {
        this.trustedMatchers = props.getTrustedCidrs().stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .map(IpAddressMatcher::new)
                .toList();
        this.expectedToken = props.getToken() == null
                ? new byte[0]
                : props.getToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String remote = request.getRemoteAddr();
        if (!isTrusted(remote)) {
            deny(response, "internal endpoint denied for " + remote);
            return;
        }
        if (!tokenMatches(request.getHeader(TOKEN_HEADER))) {
            deny(response, "invalid internal token");
            return;
        }
        chain.doFilter(request, response);
    }

    /** remote IP 가 신뢰 CIDR 중 하나에라도 속하면 true. */
    private boolean isTrusted(String remote) {
        if (remote == null || remote.isBlank()) {
            return false;
        }
        for (IpAddressMatcher m : trustedMatchers) {
            try {
                if (m.matches(remote)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // 비-IP remote(예: 테스트 환경) → 불일치로 간주.
            }
        }
        return false;
    }

    /**
     * 헤더 토큰을 기대 토큰과 상수시간 비교한다. 헤더 없음/빈 기대값은 불일치.
     * (fail-closed: expectedToken 이 비어 있으면 항상 거부)
     */
    private boolean tokenMatches(String provided) {
        if (provided == null || expectedToken.length == 0) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), expectedToken);
    }

    /** 403 을 기록하고 체인을 끊는다. */
    private void deny(HttpServletResponse response, String detail) throws IOException {
        log.warn("internal admin guard denied: {}", detail);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"error\":\"forbidden\",\"message\":\"" + detail + "\"}");
    }
}
