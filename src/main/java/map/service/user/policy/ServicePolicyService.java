package map.service.user.policy;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Explicit service terms and adult self-declaration, independent of optional AI sharing consent. */
@Service
@Transactional(readOnly = true)
public class ServicePolicyService {
    public static final String TERMS_VERSION = "2026-09-08.1";
    public static final String PRIVACY_VERSION = "2026-09-08.1";
    private static final ZoneId KST = BirthDatePolicy.KST;
    private final UserRepository users;
    private final ServicePolicyAcceptanceRepository acceptances;
    private final Clock clock;

    @Autowired
    public ServicePolicyService(UserRepository users, ServicePolicyAcceptanceRepository acceptances) {
        this(users, acceptances, Clock.systemUTC());
    }
    ServicePolicyService(UserRepository users, ServicePolicyAcceptanceRepository acceptances, Clock clock) {
        this.users = users;
        this.acceptances = acceptances;
        this.clock = clock;
    }

    public record Status(
            @JsonProperty("terms_version") String termsVersion,
            @JsonProperty("privacy_version") String privacyVersion,
            @JsonProperty("minimum_age") int minimumAge,
            boolean accepted,
            @JsonProperty("age_eligible") Boolean ageEligible,
            @JsonProperty("accepted_at") OffsetDateTime acceptedAt) {}

    public record AcceptRequest(
            @NotBlank @JsonProperty("terms_version") String termsVersion,
            @NotBlank @JsonProperty("privacy_version") String privacyVersion,
            @NotNull @AssertTrue @JsonProperty("is_18_or_older") Boolean is18OrOlder,
            @NotNull @AssertTrue @JsonProperty("terms_accepted") Boolean termsAccepted,
            @NotNull @AssertTrue @JsonProperty("privacy_accepted") Boolean privacyAccepted) {}

    public Status status(Long userId) {
        User user = users.findById(requireId(userId)).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return status(user, acceptances.findById(userId).orElse(null));
    }

    @Transactional
    public Status accept(Long userId, AcceptRequest request) {
        requireId(userId);
        if (request == null || !Boolean.TRUE.equals(request.is18OrOlder())
                || !Boolean.TRUE.equals(request.termsAccepted()) || !Boolean.TRUE.equals(request.privacyAccepted()))
            throw new CustomException(ErrorCode.POLICY_ACCEPTANCE_INVALID);
        if (!TERMS_VERSION.equals(request.termsVersion()) || !PRIVACY_VERSION.equals(request.privacyVersion()))
            throw new CustomException(ErrorCode.POLICY_VERSION_MISMATCH);
        // Serialize first acceptance/version changes with account deletion and other consent requests.
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        requireAdult(ageEligible(user));
        ServicePolicyAcceptance acceptance = acceptances.findById(userId).orElse(null);
        if (acceptance == null) {
            acceptance = acceptances.save(new ServicePolicyAcceptance(user, TERMS_VERSION, PRIVACY_VERSION,
                    OffsetDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
        } else if (!acceptance.matches(TERMS_VERSION, PRIVACY_VERSION)) {
            acceptance.accept(TERMS_VERSION, PRIVACY_VERSION,
                    OffsetDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        }
        return status(user, acceptance);
    }

    public void requireEligible(Long userId) {
        Status status = status(userId);
        requireAdult(status.ageEligible());
        if (!status.accepted()) throw new CustomException(ErrorCode.SERVICE_POLICY_REQUIRED);
    }

    /** Read fresh scalar values immediately before returning protected content. */
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void requireCurrentEligible(Long userId) {
        var current = acceptances.findCurrentEligibility(requireId(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_TOKEN));
        requireAdult(BirthDatePolicy.adult(current.getBirthDate(), LocalDate.now(clock.withZone(KST))));
        if (!Boolean.TRUE.equals(current.getAdultDeclaration())
                || !TERMS_VERSION.equals(current.getTermsVersion())
                || !PRIVACY_VERSION.equals(current.getPrivacyVersion()))
            throw new CustomException(ErrorCode.SERVICE_POLICY_REQUIRED);
    }

    private Status status(User user, ServicePolicyAcceptance acceptance) {
        Boolean eligible = ageEligible(user);
        boolean accepted = Boolean.TRUE.equals(eligible) && acceptance != null
                && acceptance.matches(TERMS_VERSION, PRIVACY_VERSION);
        return new Status(TERMS_VERSION, PRIVACY_VERSION, 18, accepted, eligible,
                acceptance == null ? null : acceptance.acceptedAt());
    }
    private Boolean ageEligible(User user) {
        return BirthDatePolicy.adult(user.getBirthDate(), LocalDate.now(clock.withZone(KST)));
    }
    private static void requireAdult(Boolean eligible) {
        if (eligible == null) throw new CustomException(ErrorCode.AGE_INFORMATION_REQUIRED);
        if (!eligible) throw new CustomException(ErrorCode.AGE_RESTRICTED);
    }

    private Long requireId(Long userId) {
        if (userId == null) throw new CustomException(ErrorCode.INVALID_TOKEN);
        return userId;
    }
}
