package map.service.user.domain.user.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import map.service.user.domain.user.dto.UserMeResponse;
import map.service.user.domain.user.dto.UserUpdateRequest;
import map.service.user.domain.user.service.UserProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
}
