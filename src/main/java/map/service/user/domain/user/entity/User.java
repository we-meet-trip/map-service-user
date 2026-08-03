package map.service.user.domain.user.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

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

    /** 생년월일 — 가입 화면이 받는 값. 선택 항목이라 NULL 가능 */
    @Column(name = "birth_date")
    private LocalDate birthDate;

    /** 성별 — 화면이 보내는 표기를 그대로 담는다. 선택 항목이라 NULL 가능 */
    @Column(name = "gender", length = 16)
    private String gender;

    /**
     * 관심사 목록.
     *
     * 개수가 정해지지 않은 문자열 목록이고 조건 검색 대상이 아니라 통째로
     * 읽고 통째로 쓴다. 값의 종류는 화면이 정하며 여기서 제한하지 않는다.
     *
     * 컬럼 타입을 못박지 않는다. 마이그레이션이 이미 타입을 정해 두었고,
     * 여기서 특정 DB 의 표기를 적으면 다른 DB 로 띄우는 테스트가 스키마를
     * 만들지 못한다.
     */
    @Column(name = "interests")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> interests;

    /** 여행 테마 목록. 관심사와 같은 이유로 목록째 담는다. */
    @Column(name = "themes")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> themes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    public User(String email, String nickname, String profileImageUrl,
                String passwordHash, AuthProvider authProvider, boolean emailVerified,
                LocalDate birthDate, String gender,
                List<String> interests, List<String> themes) {
        this.email = email;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.passwordHash = passwordHash;
        this.authProvider = authProvider;
        this.emailVerified = emailVerified;
        this.birthDate = birthDate;
        this.gender = gender;
        this.interests = interests;
        this.themes = themes;
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

    /**
     * 내 정보 화면이 보낸 값으로 프로필과 취향을 고친다.
     *
     * null 은 "이 항목은 건드리지 않는다"는 뜻이다 — 화면이 일부만 보내도
     * 나머지가 지워지지 않아야 한다. 목록을 비우려는 의도는 빈 목록으로
     * 표현한다.
     */
    public void updateDetails(String nickname, String profileImageUrl,
                              LocalDate birthDate, String gender,
                              List<String> interests, List<String> themes) {
        if (nickname != null) {
            this.nickname = nickname;
        }
        if (profileImageUrl != null) {
            this.profileImageUrl = profileImageUrl;
        }
        if (birthDate != null) {
            this.birthDate = birthDate;
        }
        if (gender != null) {
            this.gender = gender;
        }
        if (interests != null) {
            this.interests = interests;
        }
        if (themes != null) {
            this.themes = themes;
        }
    }

    public void verifyEmail() {
        this.emailVerified = true;
    }
}
