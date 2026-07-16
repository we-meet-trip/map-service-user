package map.service.user.global.config;

import map.service.user.global.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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
 *
 * jwtAuthenticationFilter/corsConfigurationSource 는 기존 빈을 그대로 재사용한다.
 */
@Configuration
public class ChatSecurityConfig {

    private static final String CHAT_API = "/api/v1/chat/**";

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
                        // 나머지(= /api/v1/chat/**)는 항상 인증 필수.
                        .anyRequest().authenticated())
                .exceptionHandling(e ->
                        e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
