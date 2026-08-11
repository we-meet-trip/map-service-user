package map.service.user.mobility.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PmVehicle — 공유 킥보드 한 대 (client 계약)
 *
 * hub /v1/mobility/pm-vehicles 의 기기 항목을 그대로 받아 client 로 전달한다.
 * 필드명은 hub 응답 키와 일치시킨다.
 *
 * provider: 사업자명. deviceId: 기기 식별자.
 * batteryLevel: 배터리 잔량(%). 발급처가 안 줄 수 있다.
 * vehicleType: 기기 종류 표기. 빈 문자열일 수 있다.
 * lat / lng: 기기 좌표.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PmVehicle(
        String provider,
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("battery_level") Integer batteryLevel,
        @JsonProperty("vehicle_type") String vehicleType,
        double lat,
        double lng
) {
}
