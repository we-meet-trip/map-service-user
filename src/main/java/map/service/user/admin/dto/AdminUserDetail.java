package map.service.user.admin.dto;

import java.time.OffsetDateTime;

/**
 * AdminUserDetail — 회원 상세(전체 이메일 노출)
 *
 * 상세 화면에서는 원문 이메일을 노출한다(상세 열람 자체가 admin 측 audit_logs 에
 * 기록됨). password_hash 등 인증 비밀은 여기에도 절대 포함하지 않는다.
 *
 * profileImageUrl : 프로필 이미지 URL(nullable).
 * updatedAt       : 최종 갱신 시각.
 */
public record AdminUserDetail(
        Long id,
        String email,
        String nickname,
        String profileImageUrl,
        String authProvider,
        boolean emailVerified,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
