package map.service.user.places.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * PlacePhotoItem — 장소 사진 한 건 (client 계약)
 *
 * hub /v1/places/photos 의 사진 항목을 그대로 받아 client 로 전달한다.
 * 필드명은 hub 응답 키와 일치시킨다.
 *
 * photoUri 는 요청할 때마다 새로 발급되는 짧은 수명의 URL 이다. BFF 는 이
 * 값을 캐시하거나 저장하지 않고 그대로 흘려보낸다 — 저장해 두면 화면에
 * 띄울 때쯤 이미 죽은 링크가 된다.
 *
 * photoUri: 이미지 URL.
 * widthPx / heightPx: 원본 크기. 없을 수 있다.
 * attributions: 제공자 표기 목록.
 * googleMapsUri: 원본 사진을 여는 지도 URL.
 * flagContentUri: 부적절한 사진을 신고하는 URL.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlacePhotoItem(
        @JsonProperty("photo_uri") String photoUri,
        @JsonProperty("width_px") Integer widthPx,
        @JsonProperty("height_px") Integer heightPx,
        List<PhotoAttribution> attributions,
        @JsonProperty("google_maps_uri") String googleMapsUri,
        @JsonProperty("flag_content_uri") String flagContentUri
) {
}
