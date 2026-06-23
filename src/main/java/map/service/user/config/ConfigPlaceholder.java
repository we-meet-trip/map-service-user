package map.service.user.config;

/**
 * ConfigPlaceholder — config 패키지의 책임을 문서화하기 위한 자리표시자 클래스
 *
 * 본 클래스는 인스턴스화되지 않으며, 빈 등록 대상도 아니다.
 * 오직 본 패키지가 담당하는 횡단 관심사 범위를 한 곳에 명시하기 위한 용도이다.
 *
 * 본 패키지의 책임:
 * - 보안 필터 체인 구성
 * - 외부/내부 HTTP 클라이언트 빈 등록 (예: AgentClientConfig 의 RestClient)
 * - Redis 연결 팩토리·템플릿 빈 등록 (예: RedisConfig)
 * - Jackson 직렬화 설정
 * - JWT 발급·검증 설정
 * - 내부 서비스 호출 시 인증 헤더를 자동 첨부하는 HTTP 클라이언트 빈
 *
 * 본 패키지의 동일 자리표시 패턴:
 * - {@link map.service.user.common.CommonPlaceholder}
 * - {@link map.service.user.auth.AuthPlaceholder}
 */
// 애플리케이션 설정 빈을 본 패키지에 정의한다.
// 책임: 보안 필터 체인 구성, HTTP 클라이언트 빈, Redis 연결 빈,
//       Jackson 직렬화 설정, JWT 발급·검증 설정,
//       내부 서비스 호출 시 인증 헤더를 자동 첨부하는 HTTP 클라이언트 빈.
public final class ConfigPlaceholder {
    private ConfigPlaceholder() {}
}
