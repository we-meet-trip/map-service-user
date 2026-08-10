package map.service.user.chat;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Claims;
import java.util.List;
import map.service.user.chat.service.ChatRoomService;
import map.service.user.chat.web.ChatRoomController;
import map.service.user.global.config.ChatSecurityConfig;
import map.service.user.global.config.CorsProperties;
import map.service.user.global.config.SecurityConfig;
import map.service.user.global.jwt.JwtService;
import map.service.user.global.ratelimit.ClientIpResolver;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.ratelimit.RateLimitService;
import map.service.user.global.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ChatSecurityConfigTest — 채팅 전용 필터체인 검증
 *
 * auth.enforced 가 기본(false)이어도 /api/v1/chat/** 는 인증이 필수임을(토큰 없으면 401,
 * 유효 토큰이면 통과) 확인한다. 또 WebSocket 핸드셰이크 경로는 permitAll 이라 인증으로
 * 막히지 않음을(401 아님) 확인한다. 실제 두 필터체인(채팅 @Order(1) + 도메인 @Order(2))과
 * 실 JWT 필터를 그대로 사용한다.
 */
@WebMvcTest(ChatRoomController.class)
@Import({ChatSecurityConfig.class, SecurityConfig.class, JwtAuthenticationFilter.class,
        RateLimitFilter.class, ClientIpResolver.class, CorsProperties.class, map.service.user.global.config.ChatProperties.class})
@DisplayName("ChatSecurityConfig 필터체인 테스트")
class ChatSecurityConfigTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private RateLimitService rateLimitService;
    @MockitoBean private ChatRoomService chatRoomService;
    @MockitoBean private map.service.user.chat.service.ChatRealtimeService chatRealtimeService;

    @Test
    @DisplayName("토큰 없이 채팅 API 접근 시 401 (auth.enforced=false 여도 인증 필수)")
    void chatRouteRequiresAuthByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/chat/rooms"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("유효 토큰이면 채팅 API 통과 (200)")
    void chatRouteProceedsWithValidToken() throws Exception {
        Claims claims = Mockito.mock(Claims.class);
        when(jwtService.validateAccessToken("tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn(7L);
        when(chatRoomService.listMyRooms(7L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/chat/rooms").header("Authorization", "Bearer tok"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("WebSocket 핸드셰이크 경로는 permitAll (인증으로 막히지 않음)")
    void handshakePathNotAuthBlocked() throws Exception {
        // 핸드셰이크 엔드포인트는 이 슬라이스에 컨트롤러로 매핑되지 않으므로 상태코드
        // 자체는 무관하다. 인증 차단(401/403)이 아니라는 점만이 permitAll 을 확인해 준다.
        int status = mockMvc.perform(get("/ws/chat")).andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isNotIn(401, 403);
    }
}
