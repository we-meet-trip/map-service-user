package map.service.user.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import map.service.user.global.config.InternalAdminProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InternalAdminGuardFilterTest — CIDR + X-Internal-Token 가드 매트릭스
 *
 * hub internal_guard 이식 필터가 (1) 신뢰 CIDR, (2) 토큰 일치 두 조건을 모두
 * 만족할 때만 체인을 진행하고, 하나라도 실패하면 403 으로 끊는지 검증한다.
 */
@DisplayName("InternalAdminGuardFilter 가드 테스트")
class InternalAdminGuardFilterTest {

    private static final String TOKEN = "secret-token";

    private InternalAdminGuardFilter filter(String expectedToken) {
        InternalAdminProperties props = new InternalAdminProperties();
        props.setTrustedCidrs(List.of("172.16.0.0/12", "10.0.0.0/8", "192.168.0.0/16"));
        props.setToken(expectedToken);
        return new InternalAdminGuardFilter(props);
    }

    private HttpServletResponse mockResponse() throws Exception {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return resp;
    }

    @Test
    @DisplayName("신뢰 CIDR + 올바른 토큰 → 체인 진행(통과)")
    void trustedAndValidToken_passes() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("10.0.0.5");
        when(req.getHeader("X-Internal-Token")).thenReturn(TOKEN);
        HttpServletResponse resp = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(TOKEN).doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verify(resp, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    @DisplayName("신뢰 CIDR + 틀린 토큰 → 403, 체인 미진행")
    void trustedButWrongToken_denied() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("172.18.0.9");
        when(req.getHeader("X-Internal-Token")).thenReturn("nope");
        HttpServletResponse resp = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(TOKEN).doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(chain, never()).doFilter(anyRequest(), anyResponse());
    }

    @Test
    @DisplayName("신뢰 CIDR + 토큰 헤더 없음 → 403")
    void trustedNoTokenHeader_denied() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("192.168.1.20");
        when(req.getHeader("X-Internal-Token")).thenReturn(null);
        HttpServletResponse resp = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(TOKEN).doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(chain, never()).doFilter(anyRequest(), anyResponse());
    }

    @Test
    @DisplayName("신뢰 안 되는 CIDR + 올바른 토큰 → 403")
    void untrustedIp_denied() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.5");
        when(req.getHeader("X-Internal-Token")).thenReturn(TOKEN);
        HttpServletResponse resp = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(TOKEN).doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(chain, never()).doFilter(anyRequest(), anyResponse());
    }

    @Test
    @DisplayName("기대 토큰 비어 있음(fail-closed) → 헤더가 있어도 403")
    void blankExpectedToken_failClosed() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("10.1.2.3");
        when(req.getHeader("X-Internal-Token")).thenReturn("");
        HttpServletResponse resp = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter("").doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(chain, never()).doFilter(anyRequest(), anyResponse());
    }

    private static HttpServletRequest anyRequest() {
        return org.mockito.ArgumentMatchers.any(HttpServletRequest.class);
    }

    private static HttpServletResponse anyResponse() {
        return org.mockito.ArgumentMatchers.any(HttpServletResponse.class);
    }
}
