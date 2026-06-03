package map.service.user.schedule;

import com.fasterxml.jackson.annotation.JsonProperty;
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
        @JsonProperty("date_end") LocalDate dateEnd
) {
}
