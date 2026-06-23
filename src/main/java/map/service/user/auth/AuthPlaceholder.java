package map.service.user.auth;

/**
 * AuthPlaceholder — auth 패키지의 책임을 문서화하기 위한 자리표시자 클래스
 *
 * 본 클래스는 인스턴스화되지 않으며, 빈 등록 대상도 아니다.
 * 인증 도메인의 책임 범위를 한 곳에 명시하기 위한 용도이다.
 *
 * 본 패키지의 책임:
 * - 이메일 / 비밀번호 기반 가입 · 로그인
 * - 외부 OAuth 공급자를 통한 로그인
 * - Access · Refresh JWT 발급과 검증
 * - 회원 탈퇴 시 관련 데이터 정합 처리
 *
 * 관련 설정:
 * - JWT secret / TTL 은 application.yml 의 jwt.* 프로퍼티로 주입된다.
 *
 * 동일 자리표시 패턴:
 * - {@link map.service.user.config.ConfigPlaceholder}
 * - {@link map.service.user.common.CommonPlaceholder}
 */
// 인증 도메인을 본 패키지에 정의한다.
// 책임: 이메일/비밀번호 기반 가입·로그인, 외부 OAuth 공급자를 통한 로그인,
//       Access·Refresh JWT 발급과 검증, 회원 탈퇴 시 관련 데이터 정합 처리.
public final class AuthPlaceholder {
    private AuthPlaceholder() {}
}
