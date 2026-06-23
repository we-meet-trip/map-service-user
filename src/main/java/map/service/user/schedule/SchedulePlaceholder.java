package map.service.user.schedule;

// 일정 도메인을 본 패키지에 정의한다.
// 책임: 일정의 생성·조회·삭제, 일별 일정 항목과 장소 스냅샷, 구간 경로의 영속화,
//       소유자 식별자 불변 보장, 외부 장소 참조의 스냅샷 보존.
/**
 * SchedulePlaceholder — schedule 패키지 책임 명세 마커
 *
 * 본 패키지의 책임을 문서로 고정하기 위한 빈 final 클래스.
 * 인스턴스화 불가하며(생성자 private), 런타임 동작 없음.
 * 패키지 책임: 일정의 생성·조회·삭제, 일별 일정 항목과 장소 스냅샷,
 * 구간 경로의 영속화, 소유자 식별자 불변 보장, 외부 장소 참조의 스냅샷 보존.
 */
public final class SchedulePlaceholder {
    private SchedulePlaceholder() {}
}
