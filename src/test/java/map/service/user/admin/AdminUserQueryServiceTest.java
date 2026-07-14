package map.service.user.admin;

import map.service.user.admin.dto.AdminUserDetail;
import map.service.user.admin.dto.AdminUserSummary;
import map.service.user.admin.dto.PageResponse;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AdminUserQueryServiceTest — 회원 조회 마스킹/상세 단위 테스트 (Mockito)
 *
 * 목록은 이메일 마스킹, 상세는 원문 노출, 없으면 empty 를 검증한다. 어떤 응답에도
 * password_hash 가 포함되지 않음은 DTO 구조(필드 부재)로 보장된다.
 */
@DisplayName("AdminUserQueryService 단위 테스트")
class AdminUserQueryServiceTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final AdminUserQueryService service = new AdminUserQueryService(repository);

    @Test
    @DisplayName("maskEmail — local part 첫 글자만 남기고 도메인 유지")
    void maskEmail_rules() {
        assertThat(AdminUserQueryService.maskEmail("alice@example.com"))
                .isEqualTo("a***@example.com");
        assertThat(AdminUserQueryService.maskEmail(null)).isNull();
        assertThat(AdminUserQueryService.maskEmail("@nolocal.com")).isEqualTo("***");
    }

    @Test
    @DisplayName("목록 검색 — 이메일이 마스킹되어 반환")
    void search_masksEmail() {
        User u = User.builder()
                .email("bob@example.com")
                .nickname("밥")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(true)
                .build();
        Page<User> page = new PageImpl<>(List.of(u), PageRequest.of(0, 20), 1);
        when(repository.findAll(any(Pageable.class))).thenReturn(page);

        PageResponse<AdminUserSummary> resp = service.search(null, 0, 20);

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).emailMasked()).isEqualTo("b***@example.com");
        assertThat(resp.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("검색어 있으면 email/nickname 부분일치 쿼리 사용")
    void search_withQuery_usesContainsQuery() {
        Page<User> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(repository.findByEmailContainingIgnoreCaseOrNicknameContainingIgnoreCase(
                any(), any(), any(Pageable.class))).thenReturn(empty);

        PageResponse<AdminUserSummary> resp = service.search("bob", 0, 20);

        assertThat(resp.items()).isEmpty();
    }

    @Test
    @DisplayName("상세 — 원문 이메일 노출, 없으면 empty")
    void detail_fullEmailAndMissing() {
        User u = User.builder()
                .email("carol@example.com")
                .nickname("캐롤")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .build();
        when(repository.findById(1L)).thenReturn(Optional.of(u));
        when(repository.findById(9L)).thenReturn(Optional.empty());

        Optional<AdminUserDetail> found = service.detail(1L);
        assertThat(found).isPresent();
        assertThat(found.get().email()).isEqualTo("carol@example.com");

        assertThat(service.detail(9L)).isEmpty();
    }
}
