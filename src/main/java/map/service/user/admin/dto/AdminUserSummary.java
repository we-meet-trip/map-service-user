package map.service.user.admin.dto;

import java.time.OffsetDateTime;

/**
 * AdminUserSummary — 회원 목록 행(마스킹된 요약)
 *
 * 목록에서는 이메일을 마스킹(a***@dom.com)하여 노출한다. password_hash 등
 * 민감 필드는 절대 포함하지 않는다.
 *
 * id            : 회원 PK.
 * emailMasked   : 마스킹된 이메일(원문 미노출). 이메일 없으면 null.
 * nickname      : 닉네임.
 * authProvider  : 인증 제공자(EMAIL/KAKAO/APPLE).
 * emailVerified : 이메일 인증 여부.
 * createdAt     : 가입 시각.
 */
public record AdminUserSummary(
        Long id,
        String emailMasked,
        String nickname,
        String authProvider,
        boolean emailVerified,
        OffsetDateTime createdAt
) {
}
