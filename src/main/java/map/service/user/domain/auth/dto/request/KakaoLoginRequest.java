package map.service.user.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import map.service.user.domain.user.entity.DeviceType;

@Getter
@NoArgsConstructor
public class KakaoLoginRequest {

    @NotBlank(message = "카카오 인가 코드는 필수입니다.")
    private String code;

    private String deviceToken;
    private DeviceType deviceType;
}
