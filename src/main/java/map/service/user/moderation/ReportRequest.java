package map.service.user.moderation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReportRequest(@NotNull UUID clientRequestId,
        @NotNull ModerationReport.ContentType contentType, @NotNull ModerationReport.Reason reason,
        @Size(max=1000) String description, @Positive Long roomId, @Positive Long messageSeq,
        @Positive Long scheduleId, UUID recommendJobId) {
    // Unknown image/base64/location fields must not silently become supported input.
    @JsonAnySetter public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported report field");
    }
}
