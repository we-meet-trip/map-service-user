package map.service.user.common;

/**
 * CommonPlaceholder — common 패키지의 책임을 문서화하기 위한 자리표시자 클래스
 *
 * 본 클래스는 인스턴스화되지 않으며, 빈 등록 대상도 아니다.
 * 본 패키지가 담당하는 횡단 관심사 범위를 한 곳에 명시하기 위한 용도이다.
 *
 * 본 패키지의 책임:
 * - 공통 응답 포맷 정의
 * - 전역 예외 핸들러 (예: GlobalExceptionHandler — agent 호출 실패 시
 *   AgentRequestException → 502, ResourceAccessException → 504 로 변환)
 * - 요청 / 응답 로깅
 * - 내부 서비스 호출 인증 헤더 검증 필터
 *
 * 동일 자리표시 패턴:
 * - {@link map.service.user.config.ConfigPlaceholder}
 * - {@link map.service.user.auth.AuthPlaceholder}
 */
// 횡단 관심사(공통 유틸·예외 처리)를 본 패키지에 정의한다.
// 책임: 공통 응답 포맷 정의, 전역 예외 핸들러, 요청·응답 로깅,
//       내부 서비스 호출 인증 헤더 검증 필터.
public final class CommonPlaceholder {
    private CommonPlaceholder() {}
}
