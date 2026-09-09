package map.service.user.recommend;

/** Public failure allowlist shared by asynchronous results and the trip facade. */
public record RecommendationFailure(String code, boolean retryable) {
    public static RecommendationFailure of(String code, Boolean retryable) {
        if (code == null) return new RecommendationFailure("generation_failed", false);
        return switch (code) {
            case "no_matching_places", "selection_invalid", "invalid_request", "quota_exceeded" ->
                    new RecommendationFailure(code, false);
            case "upstream_unavailable", "generation_timeout" ->
                    new RecommendationFailure(code, Boolean.TRUE.equals(retryable));
            default -> new RecommendationFailure("generation_failed", false);
        };
    }

    public int httpStatus() {
        return switch (code) {
            case "no_matching_places", "invalid_request" -> 422;
            case "quota_exceeded" -> 429;
            case "upstream_unavailable" -> 503;
            case "generation_timeout" -> 504;
            default -> 502;
        };
    }

    public String message() {
        return switch (code) {
            case "no_matching_places" -> "현재 조건에서 추천할 장소를 찾지 못했습니다. 선택 조건을 확인해 주세요.";
            case "selection_invalid" -> "추천 결과를 구성하지 못했습니다. 선택한 장소와 일정을 확인해 주세요.";
            case "invalid_request" -> "선택한 장소와 일정 조건을 확인해 주세요.";
            case "quota_exceeded" -> "현재 추천 요청 한도에 도달했습니다. 잠시 뒤 다시 확인해 주세요.";
            case "upstream_unavailable" -> retryable
                    ? "추천에 필요한 정보를 일시적으로 가져오지 못했습니다. 잠시 후 다시 시도해 주세요."
                    : "추천에 필요한 정보를 가져오지 못했습니다.";
            case "generation_timeout" -> "추천 생성 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.";
            default -> "추천을 생성하지 못했습니다.";
        };
    }
}
