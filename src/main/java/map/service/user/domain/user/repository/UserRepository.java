package map.service.user.domain.user.repository;

import map.service.user.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * 관리자 회원 검색(운영 콘솔 위임 조회).
     * email 또는 nickname 에 대소문자 무시 부분일치. JpaRepository.findAll(Pageable)
     * 은 검색어가 없을 때(전체 목록) 사용한다.
     */
    Page<User> findByEmailContainingIgnoreCaseOrNicknameContainingIgnoreCase(
            String email, String nickname, Pageable pageable);
}
