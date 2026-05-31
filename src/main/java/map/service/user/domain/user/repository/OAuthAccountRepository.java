package map.service.user.domain.user.repository;

import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.OAuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    Optional<OAuthAccount> findByProviderAndProviderUserId(AuthProvider provider, Long providerUserId);

    boolean existsByProviderAndProviderUserId(AuthProvider provider, Long providerUserId);
}
