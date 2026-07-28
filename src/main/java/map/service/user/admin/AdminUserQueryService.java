package map.service.user.admin;

import map.service.user.admin.dto.AdminUserDetail;
import map.service.user.admin.dto.AdminUserSummary;
import map.service.user.admin.dto.PageResponse;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * AdminUserQueryService — 운영 콘솔 위임 회원 조회
 *
 * map-service-admin 이 /internal/admin/users 로 호출한다. 목록은 이메일을
 * 마스킹하고, 상세는 원문 이메일을 노출한다. password_hash 등 인증 비밀은
 * 어떤 응답에도 담지 않는다(DTO 로만 반환, 엔티티 직렬화 금지).
 */
@Service
public class AdminUserQueryService {

    /** 페이지 크기 상한(과대 조회 방지). */
    private static final int MAX_SIZE = 100;

    private final UserRepository userRepository;

    public AdminUserQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 회원 목록 검색(마스킹). query 가 비면 전체, 있으면 email/nickname 부분일치.
     * 최신 가입순 정렬.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserSummary> search(String query, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<User> result = (query == null || query.isBlank())
                ? userRepository.findAll(pageable)
                : userRepository
                    .findByEmailContainingIgnoreCaseOrNicknameContainingIgnoreCase(
                        query.trim(), query.trim(), pageable);

        List<AdminUserSummary> items = result.getContent().stream()
                .map(AdminUserQueryService::toSummary)
                .toList();
        return new PageResponse<>(
                items,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /** 회원 상세(원문 이메일 포함). 없으면 Optional.empty. */
    @Transactional(readOnly = true)
    public Optional<AdminUserDetail> detail(Long id) {
        return userRepository.findById(id).map(u -> new AdminUserDetail(
                u.getId(),
                u.getEmail(),
                u.getNickname(),
                u.getProfileImageUrl(),
                u.getAuthProvider() == null ? null : u.getAuthProvider().name(),
                u.isEmailVerified(),
                u.getCreatedAt(),
                u.getUpdatedAt()));
    }

    private static AdminUserSummary toSummary(User u) {
        return new AdminUserSummary(
                u.getId(),
                maskEmail(u.getEmail()),
                u.getNickname(),
                u.getAuthProvider() == null ? null : u.getAuthProvider().name(),
                u.isEmailVerified(),
                u.getCreatedAt());
    }

    private static int clampSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, MAX_SIZE);
    }

    /**
     * 이메일 마스킹: local part 의 첫 글자만 남기고 나머지는 '*'. 도메인은 유지.
     * 예: alice@example.com → a***@example.com. null/형식이상은 안전 처리.
     */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            // '@' 없음/맨앞 → 전부 마스킹(도메인 없음).
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String maskedLocal = local.charAt(0) + "***";
        return maskedLocal + domain;
    }
}
