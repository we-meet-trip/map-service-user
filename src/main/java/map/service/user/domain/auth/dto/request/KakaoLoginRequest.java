package map.service.user.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import map.service.user.domain.user.entity.DeviceType;

@Getter
@NoArgsConstructor
public class KakaoLoginRequest {

    /** 앱이 카카오 SDK 로 받아 온 액세스 토큰. 서버가 발급처와 대조한 뒤에만 사용한다. */
    @NotBlank(message = "카카오 액세스 토큰은 필수입니다.")
    private String accessToken;

    private String deviceToken;
    private DeviceType deviceType;
}
