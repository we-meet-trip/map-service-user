package map.service.user.global.config;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AuthStartupValidator — 인가 시행(auth.enforced) 전제 조건 부팅 검증
 *
 * auth.enforced=true 로 기동할 때 보안상 필수 전제가 갖춰졌는지 @PostConstruct
 * 단계에서 확인하고, 위반 시 컨텍스트 초기화를 실패시켜 취약한 설정으로의
 * 기동을 차단한다. 플래그가 false(기본)이면 아무 것도 검사하지 않는다.
 *
 * 검증 항목(enforced=true 인 경우에만):
 * - JWT 키: private-key 또는 public-key 가 비어 있으면 임시 키 쌍으로 서명하게 되어
 *   재시작/다중 인스턴스 간 토큰 검증이 깨진다 → IllegalStateException.
 * - CORS: allowed-origins 가 와일드카드('*')이면 자격증명 비포함이라 해도 인가 시행
 *   환경에서 부적절 → IllegalStateException.
 *
 * authEnforced: auth.enforced 플래그.
 * jwtProperties: jwt.private-key / jwt.public-key 조회.
 * corsProperties: cors.allowed-origins 조회.
 */
@Component
public class AuthStartupValidator {

    private final boolean authEnforced;
    private final JwtProperties jwtProperties;
    private final CorsProperties corsProperties;

    public AuthStartupValidator(
            @Value("${auth.enforced:false}") boolean authEnforced,
            JwtProperties jwtProperties,
            CorsProperties corsProperties
    ) {
        this.authEnforced = authEnforced;
        this.jwtProperties = jwtProperties;
        this.corsProperties = corsProperties;
    }

    /**
     * 부팅 시 전제 조건 검증. 위반 시 IllegalStateException 으로 기동 중단.
     * enforced=false 이면 즉시 반환한다.
     */
    @PostConstruct
    public void validate() {
        if (!authEnforced) {
            return;
        }
        if (isBlank(jwtProperties.getPrivateKey()) || isBlank(jwtProperties.getPublicKey())) {
            throw new IllegalStateException(
                    "AUTH_ENFORCED=true requires JWT_PRIVATE_KEY/JWT_PUBLIC_KEY");
        }
        if (isWildcardOrigin(corsProperties.getAllowedOrigins())) {
            throw new IllegalStateException(
                    "AUTH_ENFORCED=true forbids CORS wildcard");
        }
    }

    /** null / 공백 문자열 여부. */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** allowed-origins 가 단일 '*' 와일드카드인지 여부(빈 목록은 와일드카드로 보지 않음). */
    private static boolean isWildcardOrigin(List<String> origins) {
        return origins != null && origins.size() == 1 && "*".equals(origins.get(0));
    }
}
