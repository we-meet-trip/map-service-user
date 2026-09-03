package map.service.user.weather.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDate;

/**
 * WeatherSnapshotItem — 하루치 예보 요약 한 줄
 *
 * 일정 저장 시점의 예보를 박제해 두고 나중 예보와 견주기 위한 최소 단위다.
 * 코스 재계획 판단에 쓰이는 값만 담는다 — 기온은 코스를 다시 짜게 만들지
 * 않으므로 넣지 않는다.
 *
 * date: 예보 대상 일자(KST).
 * pop: 강수확률(%). 값이 없으면 null — 견줄 수 없는 날로 취급한다.
 * sky: 하늘 상태. sunny | cloudy | rainy | snowy
 *      (TripMapping.skyToCondition 이 만드는 값과 같은 어휘를 쓴다).
 */
public record WeatherSnapshotItem(
        LocalDate date,
        Integer pop,
        String sky
) {
    /**
     * 실내/야외 판단이 갈리는 강수확률(%). agent 의 실내 가점 기준과 같은
     * 값을 쓴다 — 서버 두 곳이 다른 숫자를 쓰면 "비 온다고 알렸는데 추천은
     * 야외" 같은 어긋남이 생긴다.
     */
    public static final int WET_POP_PERCENT = 50;

    /** 비·눈이 오는 상태인지. 저장 대상이 아니라 판정용 파생값이다. */
    @JsonIgnore
    public boolean wet() {
        return (pop != null && pop >= WET_POP_PERCENT)
                || "rainy".equals(sky)
                || "snowy".equals(sky);
    }
}
