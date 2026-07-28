package map.service.user.global.config;

import map.service.user.global.security.InternalAdminGuardFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * InternalAdminSecurityConfig — /internal/** 전용 SecurityFilterChain
 *
 * map-service-admin(운영 콘솔)이 위임 호출하는 /internal/** 경로를 위한 독립
 * 필터체인이다. @Order(0) 로 기존 도메인 체인(SecurityConfig, @Order(1))보다
 * 먼저 평가되며, securityMatcher("/internal/**") 로 해당 경로만 담당한다. 따라서
 * auth.enforced 토글이나 기존 체인의 anyRequest 규칙과 완전히 격리된다.
 *
 * 접근 통제는 InternalAdminGuardFilter(CIDR + X-Internal-Token)가 전담하며,
 * Spring 인가 규칙은 permitAll(가드 통과 후 컨트롤러 진입)로 둔다. 가드 필터는
 * 빈이 아니라 여기서 직접 생성해 이 체인에만 끼운다(전역 서블릿 자동등록 방지).
 */
@Configuration
public class InternalAdminSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain internalAdminFilterChain(
            HttpSecurity http, InternalAdminProperties props) throws Exception {
        return http
                .securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(s ->
                        s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // 가드 필터가 CIDR+토큰을 검사하고, 실패 시 403 으로 체인을 끊는다.
                .addFilterBefore(
                        new InternalAdminGuardFilter(props),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
