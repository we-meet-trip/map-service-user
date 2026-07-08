package map.service.user.global.config;

import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * SecurityConfig — 단일 SecurityFilterChain 구성
 *
 * 인증 공개 엔드포인트(signup/login/kakao/refresh/logout)와 actuator(health/info)는
 * 항상 공개로 둔다. 그 외 도메인 엔드포인트의 인가 여부는 auth.enforced 플래그로
 * 전환한다.
 *
 * jwtAuthenticationFilter : 토큰이 있을 때만 SecurityContext 를 채우는 필터(요청 차단 안 함).
 * rateLimitFilter         : 인증 라우트 레이트리밋 필터.
 * corsProperties          : CORS 허용 출처 정책.
 * authEnforced            : auth.enforced 플래그. 기본 false.
 *   - false(기본): 현행 동작 보존 — .anyRequest().permitAll() 로 전 엔드포인트 공개.
 *   - true       : recommend/schedules/trip/places/reviews 및 나머지 전부 인증 필수.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter         rateLimitFilter;
    private final CorsProperties          corsProperties;
    private final boolean                 authEnforced;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RateLimitFilter rateLimitFilter,
            CorsProperties corsProperties,
            @Value("${auth.enforced:false}") boolean authEnforced
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.corsProperties = corsProperties;
        this.authEnforced = authEnforced;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> {
                    // 인증 공개 엔드포인트 6종 + actuator(health/info)는 플래그와 무관하게 공개.
                    auth
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                            .requestMatchers(HttpMethod.GET,  "/api/v1/auth/kakao").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/kakao/callback").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/token/refresh").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
                            .requestMatchers("/actuator/health", "/actuator/info").permitAll();
                    if (authEnforced) {
                        // 인가 시행: 도메인 엔드포인트와 나머지 전부 인증 필수.
                        // JWT 필터가 채운 SecurityContext 가 없으면 401(HttpStatusEntryPoint).
                        auth
                                .requestMatchers(
                                        "/api/v1/recommend/**",
                                        "/api/v1/schedules/**",
                                        "/api/v1/trip/**",
                                        "/api/v1/places/**",
                                        "/api/v1/reviews/**").authenticated()
                                .anyRequest().authenticated();
                    } else {
                        // 현행(기본) 동작 보존: 전 엔드포인트 공개. JWT 필터는 토큰이 있을 때만
                        // SecurityContext 를 채우고 요청을 차단하지 않는다(향후 인가 도입용 인프라).
                        auth.anyRequest().permitAll();
                    }
                })
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
                .build();
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setEnabled(false);
        return bean;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = corsProperties.getAllowedOrigins();
        boolean isWildcard = origins.size() == 1 && "*".equals(origins.get(0));

        if (isWildcard) {
            config.setAllowedOriginPatterns(List.of("*"));
            // allowCredentials(true)와 wildcard origin 조합 금지 (CORS 스펙 위반 + 보안 취약)
            config.setAllowCredentials(false);
        } else {
            config.setAllowedOrigins(origins);
            config.setAllowCredentials(true);
        }

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
