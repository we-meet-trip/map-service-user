package map.service.user.domain.user.controller;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import map.service.user.chat.service.ChatRealtimeService;
import map.service.user.domain.user.dto.UserMeResponse;
import map.service.user.domain.user.dto.UserUpdateRequest;
import map.service.user.domain.user.service.AccountWithdrawalService;
import map.service.user.domain.user.service.UserProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * UserController — 내 정보 조회·수정
 *
 * 마이페이지의 프로필 편집과 관심사 설정 화면이 부른다.
 *
 * 두 경로 모두 **토큰이 반드시 있어야 한다**. 인가 시행 스위치와 무관하게
 * 보안 설정에서 인증 필수로 못박혀 있다 — '내' 정보를 다루는 경로라 누구인지
 * 정해지지 않으면 무엇을 돌려줘도 틀리기 때문이다.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserProfileService userProfileService;
    private final AccountWithdrawalService withdrawalService;
    private final ChatRealtimeService realtimeService;

    @GetMapping("/me")
    public ResponseEntity<UserMeResponse> getMe(
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(userProfileService.getMe(userId));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserMeResponse> updateMe(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UserUpdateRequest request
    ) {
        return ResponseEntity.ok(userProfileService.updateMe(userId, request));
    }

    /**
     * 탈퇴.
     *
     * 방 종료 통지는 삭제가 커밋된 뒤에 보낸다. 트랜잭션 안에서 먼저 알리면 뒤이어
     * 실패해 되돌아갔을 때 살아 있는 방을 종료됐다고 알린 꼴이 된다.
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal Long userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        List<Long> closedRoomIds = withdrawalService.withdraw(userId, extractBearerToken(authHeader));
        closedRoomIds.forEach(realtimeService::broadcastRoomClosed);
        return ResponseEntity.noContent().build();
    }

    private String extractBearerToken(String header) {
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return header;
    }
}
