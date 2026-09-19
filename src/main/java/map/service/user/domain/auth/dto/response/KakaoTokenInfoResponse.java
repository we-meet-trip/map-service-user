package map.service.user.domain.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 카카오 토큰 정보 보기 응답.
 *
 * 액세스 토큰이 어느 앱에 어느 회원으로 발급됐는지를 알려 준다. 서버는 이 응답의
 * 앱 번호를 우리 앱 번호와 대조해 남의 앱 토큰을 걸러 낸다.
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KakaoTokenInfoResponse {

    /** 토큰에 묶인 회원번호. 앱마다 따로 매겨진다. */
    @JsonProperty("id")
    private Long id;

    /** 남은 유효 시간(초). */
    @JsonProperty("expires_in")
    private Integer expiresIn;

    /** 토큰을 발급한 앱 번호. */
    @JsonProperty("app_id")
    private Long appId;
}
