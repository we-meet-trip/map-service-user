package map.service.user.domain.user.service;

import lombok.RequiredArgsConstructor;
import map.service.user.domain.user.dto.UserMeResponse;
import map.service.user.domain.user.dto.UserUpdateRequest;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UserProfileService — 내 정보 조회·수정
 *
 * 마이페이지의 프로필 편집과 관심사 설정 화면이 쓰는 경로다. 그 전에는
 * 화면이 값을 앱 메모리에만 들고 있어 앱을 끄면 사라졌다.
 */
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;

    /**
     * 내 정보를 읽는다.
     *
     * userId 는 토큰에서 온다. 토큰이 없으면 호출 자체가 막히므로 여기까지
     * null 이 오지 않지만, 방어적으로 확인해 인증 실패로 돌린다 — 인증 없이
     * 도달하면 '누구의 정보인지'가 정해지지 않아 무엇을 돌려줘도 틀린다.
     */
    @Transactional(readOnly = true)
    public UserMeResponse getMe(Long userId) {
        return UserMeResponse.from(requireUser(userId));
    }

    /**
     * 내 정보를 고친다.
     *
     * 보내지 않은 항목은 그대로 둔다 — 화면이 자기가 다루는 부분만 보내기
     * 때문이다.
     */
    @Transactional
    public UserMeResponse updateMe(Long userId, UserUpdateRequest request) {
        User user = requireUser(userId);
        user.updateDetails(
                request.nickname(),
                request.profileImageUrl(),
                request.birthDate(),
                request.gender(),
                request.interests(),
                request.themes());
        return UserMeResponse.from(user);
    }

    private User requireUser(Long userId) {
        if (userId == null) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
