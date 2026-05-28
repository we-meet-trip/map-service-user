package map.service.user.config;

// 애플리케이션 설정 빈을 본 패키지에 정의한다.
// 책임: 보안 필터 체인 구성, HTTP 클라이언트 빈, Redis 연결 빈,
//       Jackson 직렬화 설정, JWT 발급·검증 설정,
//       내부 서비스 호출 시 인증 헤더를 자동 첨부하는 HTTP 클라이언트 빈.
public final class ConfigPlaceholder {
    private ConfigPlaceholder() {}
}
