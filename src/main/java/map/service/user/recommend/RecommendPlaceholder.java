package map.service.user.recommend;

// 추천 결과 처리를 본 패키지에 정의한다.
// 책임: 추천 비동기 작업 요청과 완료 이벤트 구독, 추천 작업 영속화,
//       클라이언트가 결과를 수신하기 위한 long-poll 엔드포인트,
//       재추천 횟수 제한 카운터 관리.
/**
 * RecommendPlaceholder — recommend 패키지 책임 명세 마커
 *
 * 본 패키지의 책임을 문서로 고정하기 위한 빈 final 클래스.
 * 인스턴스화 불가하며(생성자 private), 런타임 동작 없음.
 * 패키지 책임: 추천 비동기 작업 요청과 완료 이벤트 구독, 추천 작업 영속화,
 * 클라이언트가 결과를 수신하기 위한 long-poll 엔드포인트, 재추천 횟수 제한 카운터 관리.
 */
public final class RecommendPlaceholder {
    private RecommendPlaceholder() {}
}
