package map.service.user.trip;

/** A previously planned timeline needs recomputation with verified opening and activity constraints. */
public class TripTimelineException extends TripGenerationException {
    public TripTimelineException() {
        super("이동시간과 방문 가능 시간이 달라졌습니다. 장소와 활동 시간을 확인해 동선을 다시 요청해주세요.");
    }
}
