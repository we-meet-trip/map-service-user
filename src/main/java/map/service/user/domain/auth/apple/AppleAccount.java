package map.service.user.domain.auth.apple;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;

@Entity
@Table(name="apple_accounts")
@Getter
@NoArgsConstructor
public class AppleAccount {
    @Id @Column(name="user_id") private Long userId;
    @Column(nullable=false, unique=true, length=255) private String subject;
    @Column(name="refresh_token_ciphertext", columnDefinition="text") private String refreshTokenCiphertext;
    @Column(name="checked_at", nullable=false) private OffsetDateTime checkedAt;
    public AppleAccount(Long userId, String subject, String ciphertext) {
        this.userId=userId; this.subject=subject; this.refreshTokenCiphertext=ciphertext; this.checkedAt=OffsetDateTime.now();
    }
    public void refresh(String ciphertext) { this.refreshTokenCiphertext=ciphertext; checkedAt=OffsetDateTime.now(); }
}
