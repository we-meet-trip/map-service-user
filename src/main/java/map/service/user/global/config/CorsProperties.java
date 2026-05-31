package map.service.user.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {

    /** 허용 Origins. 단일 값 "*"이면 allowedOriginPatterns("*") 사용 (credentials 허용용) */
    private List<String> allowedOrigins = List.of("*");
}
