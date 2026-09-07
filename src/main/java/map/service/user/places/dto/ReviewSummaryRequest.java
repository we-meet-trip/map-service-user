package map.service.user.places.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ReviewSummaryRequest(
        @NotBlank @Size(max = 60) String query,
        @NotNull @AssertTrue Boolean consent,
        @JsonProperty("client_request_id") @NotNull UUID clientRequestId) {
}
