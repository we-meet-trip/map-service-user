package map.service.user.places.dto;

import java.util.List;

/**
 * PlacePhotosResponse — 장소 사진 조회 응답 (client 계약)
 *
 * hub /v1/places/photos 응답을 그대로 받아 client 로 전달한다.
 *
 * 사진을 못 찾았거나 조회에 실패해도 hub 는 빈 목록으로 정상 응답한다.
 * 따라서 photos 가 비어 있는 것은 오류가 아니며, 화면은 사진 영역만
 * 접으면 된다.
 *
 * query: 조회에 사용한 장소명.
 * photos: 사진 목록.
 * count: photos 길이.
 */
public record PlacePhotosResponse(
        String query,
        List<PlacePhotoItem> photos,
        int count
) {
}
