package map.service.user.schedule;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/**
 * ScheduleSaveRequest — 일정 저장 요청 본문
 *
 * ScheduleController.save (/api/v1/schedules) 의 @RequestBody.
 * jobId 로 draft 를 찾아 일정으로 영속화하기 위한 입력.
 *
 * jobId: 영속화 대상 draft 의 식별자(agent 가 발급한 UUID). JSON key "job_id".
 *        @NotBlank + @Pattern(UUID) — 형식이 어긋나면 400 으로 거부된다.
 * title: 일정 제목. nullable.
 * dateStart: 시작일. JSON key "date_start". null 이면 서비스에서 오늘로 보정.
 * dateEnd:   종료일. JSON key "date_end".   null 이면 서비스에서 dateStart 로 보정.
 *
 * 아래 3개는 저장 시점의 화면 조건을 함께 남겨 상세 조회가 같은 화면을
 * 재현하도록 하는 값이다. draft 에는 없는 정보라 client 가 생성 요청에 썼던
 * 값을 그대로 보내야 한다. 보내지 않으면 상세 조회가 기본 활동 시간대로
 * 대체하므로 방문 시각이 생성 직후와 달라질 수 있다.
 * transport: 이동수단. walk|bicycle|scooter|bus 중 하나.
 * activeStartHour / activeEndHour: 활동 시간대. JSON key "active_start_hour"
 *        / "active_end_hour". 0~24.
 */
public record ScheduleSaveRequest(
        @JsonProperty("job_id")
        @NotBlank
        @Pattern(
                regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "job_id must be a valid UUID"
        )
        String jobId,
        String title,
        @JsonProperty("date_start") LocalDate dateStart,
        @JsonProperty("date_end") LocalDate dateEnd,
        @Pattern(
                regexp = "walk|bicycle|scooter|bus",
                message = "transport must be one of walk|bicycle|scooter|bus"
        )
        String transport,
        @JsonProperty("active_start_hour")
        @Min(0) @Max(24)
        Integer activeStartHour,
        @JsonProperty("active_end_hour")
        @Min(0) @Max(24)
        Integer activeEndHour
) {
}
