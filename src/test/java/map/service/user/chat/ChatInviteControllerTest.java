package map.service.user.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Claims;
import java.time.OffsetDateTime;
import map.service.user.chat.dto.InvitePreview;
import map.service.user.chat.dto.InviteResponse;
import map.service.user.chat.dto.RoomResponse;
import map.service.user.chat.service.ChatInviteService;
import map.service.user.chat.service.ChatRealtimeService;
import map.service.user.chat.web.ChatInviteController;
import map.service.user.global.config.ChatProperties;
import map.service.user.global.config.ChatSecurityConfig;
import map.service.user.global.config.CorsProperties;
import map.service.user.global.config.SecurityConfig;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.jwt.JwtService;
import map.service.user.global.ratelimit.ClientIpResolver;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.ratelimit.RateLimitService;
import map.service.user.global.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ChatInviteControllerTest — 초대 엔드포인트 4종의 인가·응답 형태 검증
 *
 * 이 파일이 지키는 핵심은 두 가지다. 미리보기는 토큰 없이도 200 이어야 하고,
 * 참가는 같은 경로 아래 있어도 여전히 401 이어야 한다. 둘 중 하나가 무너지면
 * 링크를 받은 사람이 아무것도 못 보거나, 반대로 아무나 방에 들어오게 된다.
 *
 * 응답 키가 스네이크 케이스인지도 함께 본다. 서버에 전역 이름 규칙이 없어
 * 필드마다 표기를 따로 붙이는 구조라, 하나 빠뜨려도 컴파일은 통과하고 클라이언트만
 * 조용히 값을 못 읽는다.
 *
 * 실제 두 필터체인(채팅 @Order(1) + 도메인 @Order(2))과 실 JWT 필터를 그대로 쓴다.
 */
@WebMvcTest(ChatInviteController.class)
@Import({ChatSecurityConfig.class, SecurityConfig.class, JwtAuthenticationFilter.class,
        RateLimitFilter.class, ClientIpResolver.class, CorsProperties.class, ChatProperties.class})
@DisplayName("ChatInviteController 인가·응답 테스트")
class ChatInviteControllerTest {

    private static final String TOKEN = "abc123";
    private static final long USER = 7L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private RateLimitService rateLimitService;
    @MockitoBean private ChatInviteService inviteService;
    @MockitoBean private ChatRealtimeService realtimeService;

    @BeforeEach
    void setUp() {
        // 한도는 기본적으로 열어 두고, 막히는 경우만 각 테스트에서 따로 세운다.
        when(rateLimitService.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
    }

    /** 유효한 Bearer 토큰을 흉내 내 인증된 요청을 만든다. */
    private void authenticated() {
        Claims claims = Mockito.mock(Claims.class);
        when(jwtService.validateAccessToken("tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn(USER);
    }

    // ── 발급 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("발급 — 토큰이 없으면 401")
    void generate_requiresAuth() throws Exception {
        mockMvc.perform(post("/api/v1/chat/rooms/1/invite"))
                .andExpect(status().isUnauthorized());

        verify(inviteService, never()).generateOrRotate(any(), any());
    }

    @Test
    @DisplayName("발급 — 응답 키는 스네이크 케이스(expires_at)로 나간다")
    void generate_snakeCaseKeys() throws Exception {
        authenticated();
        OffsetDateTime expiry = OffsetDateTime.now().plusDays(7);
        when(inviteService.generateOrRotate(1L, USER))
                .thenReturn(new InviteResponse(TOKEN, "https://example.invalid/invite/" + TOKEN, 1, expiry));

        mockMvc.perform(post("/api/v1/chat/rooms/1/invite").header("Authorization", "Bearer tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(TOKEN))
                .andExpect(jsonPath("$.url").value("https://example.invalid/invite/" + TOKEN))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.expires_at").exists())
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
    }

    @Test
    @DisplayName("발급 — 사용자별 한도를 넘으면 429 RATE_001 이고 발급은 일어나지 않는다")
    void generate_rateLimited() throws Exception {
        authenticated();
        when(rateLimitService.isAllowed(eq("chat:invite:" + USER), anyInt(), any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/chat/rooms/1/invite").header("Authorization", "Bearer tok"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_001"));

        verify(inviteService, never()).generateOrRotate(any(), any());
    }

    // ── 폐기 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("폐기 — 토큰이 없으면 401")
    void revoke_requiresAuth() throws Exception {
        mockMvc.perform(delete("/api/v1/chat/rooms/1/invite"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("폐기 — 성공하면 204")
    void revoke_noContent() throws Exception {
        authenticated();

        mockMvc.perform(delete("/api/v1/chat/rooms/1/invite").header("Authorization", "Bearer tok"))
                .andExpect(status().isNoContent());

        verify(inviteService).revoke(1L, USER);
    }

    // ── 미리보기 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("미리보기 — 로그인하지 않아도 200 이고 방 정보를 받는다")
    void preview_isPublic() throws Exception {
        when(inviteService.preview(TOKEN)).thenReturn(
                new InvitePreview(3L, "속초 당일치기", 2, true, OffsetDateTime.now().plusDays(7)));

        mockMvc.perform(get("/api/v1/chat/invites/" + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room_id").value(3))
                .andExpect(jsonPath("$.title").value("속초 당일치기"))
                .andExpect(jsonPath("$.participant_count").value(2))
                .andExpect(jsonPath("$.joinable").value(true))
                .andExpect(jsonPath("$.expires_at").exists());
    }

    @Test
    @DisplayName("미리보기 — 발신 주소별 한도를 넘으면 429 이고 조회는 일어나지 않는다")
    void preview_rateLimitedByIp() throws Exception {
        when(rateLimitService.isAllowed(anyString(), anyInt(), any())).thenReturn(false);

        mockMvc.perform(get("/api/v1/chat/invites/" + TOKEN))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_001"));

        verify(inviteService, never()).preview(anyString());
    }

    @Test
    @DisplayName("미리보기 — 모르는 토큰은 404 CHAT_008")
    void preview_unknownToken() throws Exception {
        when(inviteService.preview("nope")).thenThrow(new CustomException(ErrorCode.CHAT_INVITE_INVALID));

        mockMvc.perform(get("/api/v1/chat/invites/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_008"));
    }

    // ── 참가 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("참가 — 토큰이 없으면 401 (미리보기를 열어도 참가는 닫혀 있다)")
    void join_stillRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/v1/chat/invites/" + TOKEN + "/join"))
                .andExpect(status().isUnauthorized());

        verify(inviteService, never()).join(anyString(), any());
    }

    @Test
    @DisplayName("참가 — 성공하면 방을 돌려주고 입장 안내를 남긴다")
    void join_emitsSystemMessage() throws Exception {
        authenticated();
        when(inviteService.join(TOKEN, USER)).thenReturn(new RoomResponse(
                3L, 11L, 1L, "속초 당일치기", false, OffsetDateTime.now().plusDays(7), 2, 0L));

        mockMvc.perform(post("/api/v1/chat/invites/" + TOKEN + "/join")
                        .header("Authorization", "Bearer tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room_id").value(3))
                .andExpect(jsonPath("$.schedule_id").value(11));

        verify(realtimeService).emitJoin(3L, USER);
    }

    @Test
    @DisplayName("참가 — 이미 참가한 사용자는 409 CHAT_007")
    void join_alreadyParticipant() throws Exception {
        authenticated();
        when(inviteService.join(TOKEN, USER))
                .thenThrow(new CustomException(ErrorCode.CHAT_ALREADY_PARTICIPANT));

        mockMvc.perform(post("/api/v1/chat/invites/" + TOKEN + "/join")
                        .header("Authorization", "Bearer tok"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT_007"));

        verify(realtimeService, never()).emitJoin(any(), any());
    }
}
