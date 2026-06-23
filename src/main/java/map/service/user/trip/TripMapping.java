package map.service.user.trip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import map.service.user.recommend.dto.Leg;
import map.service.user.recommend.dto.Mobility;
import map.service.user.trip.dto.BudgetRange;
import map.service.user.trip.dto.HubWeatherDaily;
import map.service.user.trip.dto.HubWeatherResponse;
import map.service.user.trip.dto.WeatherForecastItem;

/**
 * TripMapping — trip 요청/응답 변환 규칙 모음 (결정 D-6/D-7/D-8, 파생규칙 R-1~R-4)
 *
 * client 계약과 agent/hub 계약 사이의 모든 값 변환을 한 곳에 격리한다. 순수 함수만
 * 두어 단위 테스트와 규칙 변경이 쉽도록 한다(상태/주입 없음).
 */
public final class TripMapping {

    private TripMapping() {
    }

    private static final int MINUTES_PER_HOUR = 60;
    private static final int LAST_MINUTE_OF_DAY = 23 * 60 + 59; // 24:00 회피용 캡

    /**
     * 시/도 명칭 정규화 표 — client(구 명칭) → hub region_grid lv1(개편 명칭).
     *
     * 행정구역 개편으로 명칭이 바뀐 시/도만 담는다. client 의 17개 시/도 목록을
     * region_grid 의 lv1 17종과 1:1 대조한 결과, 아래 2건만 불일치였고 나머지 15건은
     * 그대로 일치한다(예: 제주는 client 도 이미 '제주특별자치도' 사용). hub 의
     * lookup_region_by_name 은 lv1 불일치 시에만 404 를 내므로, lv1 정규화만으로
     * 모든 시/도 선택이 404 없이 해석된다(시/군/구 불일치는 광역 대표 grid 로 fallback).
     *   - 강원도   → 강원특별자치도 (2023-06-11 개편)
     *   - 전라북도 → 전북특별자치도 (2024-01-18 개편)
     */
    private static final Map<String, String> PROVINCE_ALIAS = Map.of(
            "강원도", "강원특별자치도",
            "전라북도", "전북특별자치도"
    );

    /**
     * 시/도 명칭을 hub region_grid lv1 기준으로 정규화한다(결정: 잠재오류①).
     * 별칭 표에 없으면 입력을 그대로 반환(trim). null 은 그대로 통과.
     * 호출처: TripService 가 agent 위임·hub 날씨 호출 양쪽에 동일 값으로 사용.
     */
    public static String normalizeProvince(String province) {
        if (province == null) {
            return null;
        }
        String key = province.trim();
        return PROVINCE_ALIAS.getOrDefault(key, key);
    }

    /**
     * client transport → agent Mobility (결정 D-7).
     * scooter→bicycle, bus→transit, walk/bicycle 은 동일. 그 외는 거부(400 유도).
     */
    public static Mobility toAgentMobility(String transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport is null");
        }
        return switch (transport.trim().toLowerCase()) {
            case "walk" -> Mobility.WALK;
            case "bicycle", "scooter" -> Mobility.BICYCLE;
            case "bus" -> Mobility.TRANSIT;
            default -> throw new IllegalArgumentException("unsupported transport: " + transport);
        };
    }

    /** budget{min,max} → agent 단일 budget. max 사용 (결정 D-7). */
    public static int toAgentBudget(BudgetRange budget) {
        return budget.max();
    }

    /**
     * 활동 시각(0~24 정수) → LocalTime. 24 는 23:59 로 캡(LocalTime 은 24:00 불가),
     * 음수는 00:00 으로 보정한다. agent DateRange.time_start/time_end 로 전달된다.
     */
    public static java.time.LocalTime hourToLocalTime(int hour) {
        if (hour >= 24) {
            return java.time.LocalTime.of(23, 59);
        }
        if (hour < 0) {
            return java.time.LocalTime.of(0, 0);
        }
        return java.time.LocalTime.of(hour, 0);
    }

    /** transport_to_next.label — client 원본 transport 기준 한글 라벨 (R-3). */
    public static String transportLabel(String transport) {
        String t = transport == null ? "" : transport.trim().toLowerCase();
        return switch (t) {
            case "walk" -> "이동: 도보";
            case "bicycle" -> "이동: 자전거";
            case "scooter" -> "이동: 킥보드";
            case "bus" -> "이동: 버스";
            default -> "이동";
        };
    }

    /**
     * stop 시각 "HH:mm" — 활동 시간대 [startHour, endHour] 균등 분배 (R-2).
     * total<=1 이면 시작 시각. endHour<=startHour 면 전 stop 시작 시각으로 고정.
     */
    public static String stopTime(int startHour, int endHour, int index, int total) {
        int clock;
        if (total <= 1 || endHour <= startHour) {
            clock = startHour * MINUTES_PER_HOUR;
        } else {
            int span = (endHour - startHour) * MINUTES_PER_HOUR;
            int step = span / (total - 1);
            clock = startHour * MINUTES_PER_HOUR + index * step;
        }
        if (clock > LAST_MINUTE_OF_DAY) {
            clock = LAST_MINUTE_OF_DAY;
        }
        int hh = clock / MINUTES_PER_HOUR;
        int mm = clock % MINUTES_PER_HOUR;
        return String.format("%02d:%02d", hh, mm);
    }

    /** total_duration_minutes = 이동 구간 합 (R-1). */
    public static int totalDurationMinutes(List<Leg> legs) {
        if (legs == null) {
            return 0;
        }
        int sum = 0;
        for (Leg leg : legs) {
            sum += leg.estimatedDurationMin();
        }
        return sum;
    }

    /** hub sky_condition(한글) → client condition(sunny/cloudy/rainy/snowy) (R-4). */
    public static String skyToCondition(String sky) {
        if (sky == null || sky.isBlank()) {
            return "cloudy";
        }
        if (sky.contains("눈")) {
            return "snowy";
        }
        if (sky.contains("비") || sky.contains("소나기")) {
            return "rainy";
        }
        if (sky.contains("맑")) {
            return "sunny";
        }
        return "cloudy"; // 구름많음 · 구름조금 · 흐림 등
    }

    /**
     * hub 날씨 → client weather_forecast (결정 D-6).
     * client 가 정수 필드를 엄격 캐스팅하므로 temp/precip 중 하나라도 null 인 항목은
     * 제외한다(크래시 방지). 데이터가 없으면 빈 리스트.
     */
    public static List<WeatherForecastItem> toWeatherForecast(HubWeatherResponse hub) {
        List<WeatherForecastItem> out = new ArrayList<>();
        if (hub == null || hub.daily() == null) {
            return out;
        }
        for (HubWeatherDaily d : hub.daily()) {
            if (d == null || d.date() == null
                    || d.tempMin() == null || d.tempMax() == null
                    || d.precipitationProb() == null) {
                continue;
            }
            out.add(new WeatherForecastItem(
                    d.date().toString(),
                    skyToCondition(d.skyCondition()),
                    d.tempMax(),
                    d.tempMin(),
                    d.precipitationProb()
            ));
        }
        return out;
    }
}
