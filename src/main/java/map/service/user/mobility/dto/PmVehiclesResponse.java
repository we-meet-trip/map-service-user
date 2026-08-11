package map.service.user.mobility.dto;

import java.util.List;

/**
 * PmVehiclesResponse — 공유 킥보드 조회 응답 (client 계약)
 *
 * hub /v1/mobility/pm-vehicles 응답을 그대로 받아 client 로 전달한다.
 *
 * status: 조회 결과 구분.
 *   "ok"          — 조회에 성공했다. 주변에 기기가 없으면 빈 목록이며
 *                   그것도 정상이다.
 *   "unavailable" — 사업자 전부에서 조회에 실패했다. vehicles 는 빈 목록.
 * vehicles: 요청 좌표 주변의 기기.
 * count: vehicles 길이.
 *
 * 사업자별로 따로 물어 합치므로 일부만 실패할 수 있다. 그 경우도 "ok" 다 —
 * 한 사업자의 장애로 나머지가 함께 사라지면 화면이 실제보다 비어 보인다.
 */
public record PmVehiclesResponse(
        String status,
        List<PmVehicle> vehicles,
        int count
) {
}
