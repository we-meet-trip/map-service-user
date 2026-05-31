package map.service.user.domain.user.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false, nullable = false)
    private Long id;

    /** 이메일 — Kakao 동의 거부 시 NULL 가능. UNIQUE 제약은 NULL 제외 적용 */
    @Column(unique = true)
    private String email;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    /** BCrypt 60자. Kakao 단독 사용자는 NULL */
    @Column(name = "password_hash", length = 60)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 16)
    private AuthProvider authProvider;

    @Column(name = "is_email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    public User(String email, String nickname, String profileImageUrl,
                String passwordHash, AuthProvider authProvider, boolean emailVerified) {
        this.email = email;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.passwordHash = passwordHash;
        this.authProvider = authProvider;
        this.emailVerified = emailVerified;
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public void updateProfile(String nickname, String profileImageUrl) {
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
    }

    public void verifyEmail() {
        this.emailVerified = true;
    }
}
