package map.service.user.global.config;

import map.service.user.global.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * ChatSecurityConfig — 채팅 전용 SecurityFilterChain
 *
 * /api/v1/chat/** 와 WebSocket 핸드셰이크 경로만 담당하는 독립 필터체인이다.
 * @Order(1) 로 도메인 체인(SecurityConfig, @Order(2))보다 먼저 평가되고,
 * securityMatcher 로 채팅 경로만 매칭하므로 auth.enforced 토글과 완전히 격리된다.
 *
 * 인가 규칙:
 * - /api/v1/chat/** : 항상 인증 필수. 로그인이 전제인 기능이므로 auth.enforced 값과
 *   무관하게 토큰이 없으면 401 을 반환한다(HttpStatusEntryPoint).
 * - 핸드셰이크 경로 : permitAll. 브라우저/네이티브 클라이언트의 업그레이드 요청은
 *   Authorization 헤더를 싣기 어려우므로, 실제 사용자 인증은 STOMP CONNECT 프레임에서
 *   한 단계 위 계층이 수행한다(여기서는 핸드셰이크만 통과시킨다).
 * - 초대 미리보기 GET : permitAll. 링크를 받은 사람은 정의상 아직 로그인하지 않았고,
 *   이 호출은 아무것도 바꾸지 않는다. 로그인부터 시키면 자신이 무엇에 초대받았는지
 *   모르는 채 가입해야 한다. 남용은 IP 별 한도로 막는다.
 *
 * 미리보기만 여는 것이 중요하다. 참가(POST)는 인증을 유지해야 하므로 메서드를
 * 고정한다. 초대 토큰은 URL 안전 base64 라 슬래시가 없어서, 한 세그먼트 패턴이
 * 뒤따르는 /join 까지 삼킬 수 없다.
 *
 * jwtAuthenticationFilter/corsConfigurationSource 는 기존 빈을 그대로 재사용한다.
 */
@Configuration
public class ChatSecurityConfig {

    private static final String CHAT_API = "/api/v1/chat/**";
    private static final String CHAT_INVITE_PREVIEW = "/api/v1/chat/invites/{token}";

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsConfigurationSource corsConfigurationSource;
    private final ChatProperties chatProperties;

    public ChatSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                              CorsConfigurationSource corsConfigurationSource,
                              ChatProperties chatProperties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.corsConfigurationSource = corsConfigurationSource;
        this.chatProperties = chatProperties;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain chatFilterChain(HttpSecurity http) throws Exception {
        // 핸드셰이크 경로는 설정값(chat.ws-endpoint)에서 가져와 실제 엔드포인트와 항상 일치시킨다.
        String wsPath = chatProperties.getWsEndpoint();
        String wsPathSub = wsPath + "/**";
        return http
                .securityMatcher(CHAT_API, wsPath, wsPathSub)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 핸드셰이크는 통과시키고 인증은 CONNECT 프레임에서 수행한다.
                        .requestMatchers(wsPath, wsPathSub).permitAll()
                        // 초대 미리보기는 조회만 하므로 연다. 참가(POST)는 열지 않는다.
                        .requestMatchers(HttpMethod.GET, CHAT_INVITE_PREVIEW).permitAll()
                        // 나머지(= /api/v1/chat/**)는 항상 인증 필수.
                        .anyRequest().authenticated())
                .exceptionHandling(e ->
                        e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
