package map.service.user.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import java.util.LinkedHashMap;
import java.util.Map;
import map.service.user.recommend.dto.DateRange;
import map.service.user.recommend.dto.Leg;
import map.service.user.recommend.dto.Place;
import map.service.user.recommend.dto.RecommendRequest;
import map.service.user.recommend.dto.RecommendResponse;
import map.service.user.recommend.dto.SelectedPlace;
import map.service.user.trip.dto.TransportToNext;
import map.service.user.trip.dto.TripGenerateResponse;
import map.service.user.trip.dto.TripStop;
import map.service.user.trip.dto.WeatherForecastItem;
import org.junit.jupiter.api.Test;

/**
 * 밖으로 나가는 요청·응답의 모양을 고정한다.
 *
 * <p>보는 것은 필드 이름(직렬화 이름 기준)과 타입뿐이다. 값은 모델 응답이
 * 섞여 있어 실행할 때마다 달라지므로 고정할 수 없다.
 *
 * <p>왜 필요한가: 내부를 고치다 응답 레코드에 필드를 하나 얹어도 받는 쪽은
 * 모르는 필드를 무시하도록 되어 있어 그 순간에는 아무도 실패하지 않는다.
 * 한참 뒤에야 계약이 어긋난 것을 발견하게 되므로 여기서 미리 막는다.
 *
 * <p>고칠 때: 계약을 의도적으로 바꾸는 경우에만 골든을 함께 고친다. 빨간불이
 * 뜨면 먼저 "이 필드가 정말 나가야 하는가" 를 묻고, 답이 예일 때만 갱신한다.
 */
class ContractFreezeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 패키지 접두사를 떼어 타입 이름을 읽기 좋게 만든다. */
    private static String shortType(String typeName) {
        return typeName.replaceAll("[a-zA-Z0-9_$]+\\.", "");
    }

    /**
     * 직렬화 모양을 {@code 이름 -> 타입} 으로 만든다.
     *
     * <p>이름은 Jackson 에게 직접 묻는다. 리플렉션으로 레코드 컴포넌트의
     * 애노테이션을 읽으면 안 되는데, {@code @JsonProperty} 는 대상에
     * RECORD_COMPONENT 가 없어 필드·접근자·생성자 인자로 흩어져 붙기
     * 때문이다. 실제로 나가는 이름을 아는 것은 Jackson 뿐이다.
     */
    private static Map<String, String> shape(Class<?> type) {
        BeanDescription desc = MAPPER.getSerializationConfig()
                .introspect(MAPPER.getTypeFactory().constructType(type));
        Map<String, String> out = new LinkedHashMap<>();
        for (BeanPropertyDefinition p : desc.findProperties()) {
            out.put(p.getName(),
                    shortType(p.getPrimaryType().toCanonical()));
        }
        return out;
    }

    private static Map<String, String> of(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    // ── agent 로 나가는 요청 ────────────────────────────────────────

    @Test
    void recommendRequestShapeIsFrozen() {
        assertThat(shape(RecommendRequest.class)).isEqualTo(of(
                "date", "DateRange",
                "budget", "Integer",
                "theme", "List<String>",
                "mobility", "Mobility",
                "province", "String",
                "city", "String",
                "schedule_id", "String",
                "stage", "String",
                "exclude", "List<String>",
                "places", "List<SelectedPlace>",
                "optimize", "boolean"));
    }

    @Test
    void dateRangeShapeIsFrozen() {
        assertThat(shape(DateRange.class)).isEqualTo(of(
                "date_start", "LocalDate",
                "date_end", "LocalDate",
                "time_start", "LocalTime",
                "time_end", "LocalTime"));
    }

    @Test
    void selectedPlaceShapeIsFrozen() {
        assertThat(shape(SelectedPlace.class)).isEqualTo(of(
                "name", "String",
                "address", "String",
                "lat", "double",
                "lng", "double",
                "day", "Integer",
                "content_id", "String",
                "category", "String"));
    }

    // ── agent 결과를 담는 내부 표현 ─────────────────────────────────

    @Test
    void recommendResponseShapeIsFrozen() {
        assertThat(shape(RecommendResponse.class)).isEqualTo(of(
                "job_id", "String",
                "status", "String",
                "places", "List<Place>",
                "visit_order", "List<Integer>",
                "legs", "List<Leg>",
                "clothing", "String",
                "error", "String",
                "retry_after_seconds", "Integer",
                "warnings", "List<String>",
                "timeline_status", "String"));
    }

    @Test
    void placeShapeIsFrozen() {
        assertThat(shape(Place.class)).isEqualTo(of(
                "place_id", "int",
                "day", "int",
                "name", "String",
                "address", "String",
                "lat", "double",
                "lng", "double",
                "recommended_visit_time", "String",
                "content_id", "String",
                "source", "String",
                "category", "String",
                "grounded", "Boolean",
                "place_url", "String",
                "reason", "String",
                "bullets", "List<String>",
                "stay_minutes", "Integer",
                "visit_start", "String",
                "visit_end", "String"));
    }

    @Test
    void legShapeIsFrozen() {
        assertThat(shape(Leg.class)).isEqualTo(of(
                "from", "int",
                "to", "int",
                "mode", "Mobility",
                "estimated_distance_km", "double",
                "estimated_duration_min", "int"));
    }

    // ── client 로 나가는 응답 ───────────────────────────────────────

    @Test
    void tripGenerateResponseShapeIsFrozen() {
        assertThat(shape(TripGenerateResponse.class)).isEqualTo(of(
                "trip_id", "String",
                "total_duration_minutes", "int",
                "stops", "List<TripStop>",
                "weather_forecast", "List<WeatherForecastItem>",
                "warnings", "List<String>",
                "timeline_status", "String"));
    }

    @Test
    void tripStopShapeIsFrozen() {
        assertThat(shape(TripStop.class)).isEqualTo(of(
                "order", "int",
                "day", "int",
                "name", "String",
                "address", "String",
                "time", "String",
                "latitude", "double",
                "longitude", "double",
                "transport_to_next", "TransportToNext",
                "source", "String",
                "category", "String",
                "grounded", "Boolean",
                "place_id", "Integer",
                "place_url", "String",
                "reason", "String",
                "bullets", "List<String>",
                "end_time", "String",
                "stay_minutes", "Integer",
                "content_id", "String"));
    }

    @Test
    void transportToNextShapeIsFrozen() {
        assertThat(shape(TransportToNext.class)).isEqualTo(of(
                "type", "String",
                "label", "String",
                "duration_minutes", "int",
                "distance_km", "double",
                "path", "List<List<Double>>"));
    }

    @Test
    void weatherForecastItemShapeIsFrozen() {
        assertThat(shape(WeatherForecastItem.class)).isEqualTo(of(
                "date", "String",
                "condition", "String",
                "temp_high", "int",
                "temp_low", "int",
                "precipitation_probability", "int"));
    }
}
