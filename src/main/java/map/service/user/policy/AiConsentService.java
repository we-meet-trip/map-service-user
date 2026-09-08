package map.service.user.policy;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.recommend.RecommendJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Account-scoped permission, separate from age/service policy and learning consent. */
@Service
@Transactional(readOnly = true)
public class AiConsentService {
    public static final String VERSION = "2026-09-08.1";
    public static final String REQUEST_PERMITS = "map.ai.permits";
    private static final List<String> SCOPES = List.of("trip", "vision", "review_summary");
    private final UserRepository users;
    private final AiConsentRepository consents;
    private final AiJobConsentRepository bindings;
    private final RecommendJobRepository jobs;
    private final ServicePolicyService policy;
    private final map.service.user.global.jwt.JwtService jwt;
    public AiConsentService(UserRepository users, AiConsentRepository consents,
            AiJobConsentRepository bindings, RecommendJobRepository jobs, ServicePolicyService policy, map.service.user.global.jwt.JwtService jwt) {
        this.users = users; this.consents = consents; this.bindings = bindings; this.jobs = jobs; this.policy = policy; this.jwt = jwt;
    }
    public record Status(String scope, @JsonProperty("policy_version") String policyVersion, boolean accepted,
            @JsonProperty("include_location") boolean includeLocation, long revision,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {}
    public record Grant(@NotBlank @JsonProperty("policy_version") String policyVersion,
            @NotNull @AssertTrue Boolean accepted,
            @NotNull @JsonProperty("include_location") Boolean includeLocation,
            @NotNull @PositiveOrZero @JsonProperty("expected_revision") Long expectedRevision) {}
    public record Permit(Long userId, String scope, long revision, boolean includeLocation, String accessToken) {
        @Override public String toString() { return "AiPermit[scope=" + scope + ",revision=" + revision + "]"; }
    }

    public List<Status> status(Long userId) {
        try {
            requireUser(userId, false);
            return SCOPES.stream().map(scope -> status(userId, scope)).toList();
        } catch (CustomException denied) { throw denied; }
        catch (RuntimeException unavailable) { throw unavailable(); }
    }
    private Status status(Long userId, String scope) {
        var current = consents.current(key(userId, scope)).orElse(null);
        boolean accepted = current != null && Boolean.TRUE.equals(current.getAccepted()) && VERSION.equals(current.getPolicyVersion());
        return new Status(scope, VERSION, accepted, accepted && Boolean.TRUE.equals(current.getIncludeLocation()),
                current == null ? 0 : current.getRevision(), current == null ? null : current.getUpdatedAt());
    }
    @Transactional
    public Status grant(Long userId, String scope, Grant request) {
        validateScope(scope);
        if (request == null || !Boolean.TRUE.equals(request.accepted()) || request.includeLocation() == null
                || request.expectedRevision() == null || request.expectedRevision() < 0
                || (!scope.equals("vision") && request.includeLocation()))
            throw new CustomException(ErrorCode.POLICY_ACCEPTANCE_INVALID);
        if (!VERSION.equals(request.policyVersion())) throw new CustomException(ErrorCode.POLICY_VERSION_MISMATCH);
        return change(userId, scope, request.expectedRevision(), true, request.includeLocation());
    }
    @Transactional
    public Status revoke(Long userId, String scope, long expectedRevision) {
        validateScope(scope);
        if (expectedRevision < 0) throw new CustomException(ErrorCode.POLICY_ACCEPTANCE_INVALID);
        return change(userId, scope, expectedRevision, false, false);
    }
    private Status change(Long userId, String scope, long expected, boolean accepted, boolean location) {
        try {
            User user = requireUser(userId, true); // serializes grant/revoke and account deletion
            validateToken(userId, currentAccessToken());
            if (accepted) policy.requireCurrentEligible(userId);
            AiConsent row = consents.findById(key(userId, scope)).orElseGet(() -> new AiConsent(user, scope));
            if (row.revision != expected) throw new CustomException(ErrorCode.AI_CONSENT_CONFLICT);
            if (row.revision == 0 || row.accepted != accepted || row.includeLocation != location || !VERSION.equals(row.policyVersion)) {
                row.policyVersion = VERSION; row.accepted = accepted; row.includeLocation = location;
                row.revision = Math.addExact(row.revision, 1); row.updatedAt = OffsetDateTime.now();
                consents.saveAndFlush(row);
            }
            return new Status(scope, VERSION, row.accepted, row.includeLocation, row.revision, row.updatedAt);
        } catch (CustomException denied) { throw denied; }
        catch (RuntimeException unavailable) { throw unavailable(); }
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public Permit open(Long userId, String scope) {
        try {
            validateScope(scope);
            policy.requireCurrentEligible(userId);
            Status current = status(userId, scope);
            if (!current.accepted()) throw new CustomException(ErrorCode.AI_CONSENT_REQUIRED);
            Permit permit = new Permit(userId, scope, current.revision(), current.includeLocation(), currentAccessToken());
            remember(permit);
            return permit;
        } catch (CustomException denied) { throw denied; }
        catch (RuntimeException unavailable) { throw unavailable(); }
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public void requireCurrent(Permit permit) {
        try {
            validateToken(permit.userId(), permit.accessToken());
        } catch (CustomException denied) { throw denied; }
        catch (RuntimeException unavailable) { throw unavailable(); }
        Permit current = open(permit.userId(), permit.scope());
        if (current.revision() != permit.revision()) throw new CustomException(ErrorCode.AI_CONSENT_CHANGED);
    }
    @Transactional
    public void bindJob(String jobId, Permit permit) {
        try {
            User user = requireUser(permit.userId(), true);
            requireCurrent(permit);
            UUID id = UUID.fromString(jobId);
            var old = bindings.findById(id).orElse(null);
            if (old != null && (!old.userId.equals(user.getId()) || old.revision != permit.revision()))
                throw new CustomException(ErrorCode.AI_CONSENT_CHANGED);
            if (old == null) bindings.saveAndFlush(new AiJobConsent(id, user, permit.revision()));
        } catch (CustomException denied) { throw denied; }
        catch (RuntimeException unavailable) { throw unavailable(); }
    }
    @Transactional
    public void requireJob(String jobId, Long userId) {
        try {
            requireUser(userId, true);
            var job = jobs.findById(UUID.fromString(jobId)).orElseThrow(() -> new CustomException(ErrorCode.RECOMMEND_NOT_OWNER));
            if (userId == null || !userId.equals(job.getOwnerUserId())) throw new CustomException(ErrorCode.RECOMMEND_NOT_OWNER);
            if ("route".equals(job.getMode())) return; // deterministic OSRM route does not use external AI
            Permit current = open(userId, "trip");
            var binding = bindings.findById(UUID.fromString(jobId)).orElseThrow(() -> new CustomException(ErrorCode.AI_CONSENT_CHANGED));
            if (!userId.equals(binding.userId) || binding.revision != current.revision())
                throw new CustomException(ErrorCode.AI_CONSENT_CHANGED);
        } catch (CustomException denied) { throw denied; }
        catch (RuntimeException unavailable) { throw unavailable(); }
    }
    private User requireUser(Long userId, boolean lock) {
        if (userId == null) throw new CustomException(ErrorCode.INVALID_TOKEN);
        return (lock ? users.findByIdForUpdate(userId) : users.findById(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_TOKEN));
    }
    private static void validateScope(String scope) {
        if (!SCOPES.contains(scope)) throw new CustomException(ErrorCode.POLICY_ACCEPTANCE_INVALID);
    }
    private static String key(Long userId, String scope) { return userId + ":" + scope; }
    private static CustomException unavailable() { return new CustomException(ErrorCode.AI_CONSENT_UNAVAILABLE); }
    private void validateToken(Long userId, String token) {
        if (token != null && !userId.equals(jwt.extractUserId(jwt.validateAccessToken(token))))
            throw new CustomException(ErrorCode.INVALID_TOKEN);
    }
    private static String currentAccessToken() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String authorization = attrs.getRequest().getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) return authorization.substring(7);
        }
        return null;
    }
    private static void remember(Permit permit) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            Object old = attrs.getRequest().getAttribute(REQUEST_PERMITS);
            // Retain the first revision even if a service rechecks after a revoke/regrant.
            var permits = new LinkedHashSet<Permit>();
            if (old instanceof Set<?> set) for (Object p : set) if (p instanceof Permit saved) permits.add(saved);
            permits.add(permit);
            attrs.getRequest().setAttribute(REQUEST_PERMITS, permits);
        }
    }
}
