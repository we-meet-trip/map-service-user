package map.service.user.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * InternalAdminProperties — /internal/admin/** 가드 설정
 *
 * map-service-admin(운영 콘솔)이 위임 호출하는 /internal/admin/** 엔드포인트를
 * 보호하는 CIDR 화이트리스트 + 관리자 전용 토큰 설정을 담는다. hub 의 internal_guard
 * (CIDR + X-Internal-Token 상수시간 비교)를 Spring 으로 이식한 것이다.
 *
 * - trustedCidrs : 호출을 허용할 사설 CIDR 목록(기본 사설 대역). map-net 컨테이너
 *                  네트워크 내부 호출만 통과시킨다.
 * - token        : X-Internal-Token 기대값. USER_ADMIN_INTERNAL_TOKEN 을 사용한다. 일반 서비스 토큰과
 *                  동일하거나 비어 있으면 헤더가 있어도
 *                  일치할 수 없어 사실상 접근이 차단된다(fail-closed).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "internal.admin")
public class InternalAdminProperties {

    /** 허용 CIDR 목록. 기본 사설 대역(hub HUB_INTERNAL_TRUSTED_CIDRS 규약 미러). */
    private List<String> trustedCidrs = List.of(
            "172.16.0.0/12", "10.0.0.0/8", "192.168.0.0/16");

    /** X-Internal-Token 기대값(USER_ADMIN_INTERNAL_TOKEN, 일반 서비스 토큰과 달라야 함). */
    private String token = "";

    /** Misconfiguration check only; this value never authorizes admin requests. */
    private String serviceToken = "";
}
