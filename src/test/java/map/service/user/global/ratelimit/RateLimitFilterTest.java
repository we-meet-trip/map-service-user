package map.service.user.global.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private final RateLimitService service = mock(RateLimitService.class);
    private final ClientIpResolver resolver = mock(ClientIpResolver.class);
    private final RateLimitFilter filter =
            new RateLimitFilter(service, new ObjectMapper(), resolver);

    @Test
    void 같은_처리기로_가는_요청은_주소_표기가_달라도_한_열쇠를_쓴다() throws Exception {
        // 원시 URI 를 열쇠로 쓰면 인코딩·경로 파라미터·이어진 슬래시가 각각
        // 다른 열쇠를 받아 로그인 시도 제한을 그냥 지나간다.
        List<String> keys = new ArrayList<>();
        when(resolver.resolve(any())).thenReturn("203.0.113.9");
        when(service.isAllowed(any(), anyInt(), any())).thenAnswer(call -> {
            keys.add(call.getArgument(0));
            return true;
        });

        for (String uri : List.of(
                "/api/v1/auth/login",
                "/api/v1/auth/%6Cogin",
                "/api/v1/auth/login;jsessionid=abc",
                "//api/v1/auth/login")) {
            filter.doFilter(new MockHttpServletRequest("POST", uri),
                    new MockHttpServletResponse(), mock(FilterChain.class));
        }

        assertThat(keys).hasSize(4)
                .containsOnly("POST:/api/v1/auth/login:203.0.113.9");
    }
}
