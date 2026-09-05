package map.service.user.schedule.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * ScheduleReviseRequest — 저장된 일정 수정 요청 본문
 *
 * ScheduleController.revise (PUT /api/v1/schedules/{id}) 의 @RequestBody.
 *
 * 방문지를 하나씩 고치는 통로가 아니다. 화면이 장소를 더하고 빼고 순서를
 * 바꾼 뒤 그 목록으로 동선을 새로 만들면(POST /api/v1/trip/route) 새 작업
 * 식별자가 나오는데, 그 결과를 기존 일정 자리에 갈아 끼우는 것이 이 요청이다.
 * 방문 순서·이동 구간·시각이 서로 맞물려 있어 한 자리만 고칠 수 없기 때문이다.
 *
 * jobId: 방금 만든 동선의 작업 식별자. JSON key "job_id". @Pattern(UUID).
 *        이 식별자의 초안이 남아 있어야 한다 — 만든 직후에 부르는 요청이라
 *        사라졌다면 그 사이에 무언가 어긋난 것이므로 404 로 끝난다.
 * transport / activeStartHour / activeEndHour: 함께 바뀌었으면 싣는다.
 *        보내지 않으면 저장돼 있던 값을 그대로 둔다.
 */
public record ScheduleReviseRequest(
        @JsonProperty("job_id")
        @NotBlank
        @Pattern(
                regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "job_id must be a valid UUID"
        )
        String jobId,
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
