package map.service.user.places;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.places.dto.ReviewSummaryRequest;
import map.service.user.places.dto.ReviewSummaryResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** Explicit summary creation. Redis loss fails closed before any provider request. */
@Service
public class ReviewSummaryGenerationService {
    // Atomic across replicas: receipt claim, per-place exclusion and six new requests/minute.
    // A failed/ambiguous provider request retains its pending receipt for 30 minutes, so
    // resending the same UUID cannot cause another charge. No raw query or user ID is stored.
    static final DefaultRedisScript<String> CLAIM = new DefaultRedisScript<>("""
            local old = redis.call('GET', KEYS[1])
            if old then return 'EXISTING:' .. old end
            if redis.call('EXISTS', KEYS[2]) == 1 then return 'BUSY' end
            local count = redis.call('INCR', KEYS[3])
            if count == 1 then redis.call('EXPIRE', KEYS[3], 60) end
            if count > 6 then return 'LIMIT' end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', 1800)
            redis.call('SET', KEYS[2], ARGV[2], 'EX', 300)
            return 'START'
            """, String.class);
    static final DefaultRedisScript<Long> COMPLETE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', 1800)
            if redis.call('GET', KEYS[2]) == ARGV[3] then redis.call('DEL', KEYS[2]) end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ReviewSummaryService summaries;
    private final ObjectMapper mapper;

    public ReviewSummaryGenerationService(
            @Qualifier("cacheRedisTemplate") StringRedisTemplate redis,
            ReviewSummaryService summaries, ObjectMapper mapper) {
        this.redis = redis;
        this.summaries = summaries;
        this.mapper = mapper;
    }

    public ReviewSummaryResponse generate(Long userId, ReviewSummaryRequest request) {
        if (userId == null) throw new CustomException(ErrorCode.INVALID_TOKEN);
        if (request == null || !Boolean.TRUE.equals(request.consent())
                || request.clientRequestId() == null || request.query() == null
                || request.query().isBlank() || request.query().length() > 60) {
            throw new IllegalArgumentException("Explicit summary consent and a valid request are required");
        }
        String fingerprint = hash(request.query().strip().toLowerCase(Locale.ROOT));
        String owner = hash(userId + ":" + request.clientRequestId());
        String receiptKey = "reviews:explicit:v1:request:" + owner;
        String placeKey = "reviews:explicit:v1:place:" + fingerprint;
        String rateKey = "reviews:explicit:v1:rate:" + hash(userId.toString());
        try {
            String pending = mapper.writeValueAsString(new Receipt(fingerprint, false, List.of(), 0));
            String claim = redis.execute(CLAIM, List.of(receiptKey, placeKey, rateKey), pending, owner);
            if ("LIMIT".equals(claim)) throw new CustomException(ErrorCode.RATE_LIMIT_EXCEEDED);
            if ("BUSY".equals(claim)) throw new CustomException(ErrorCode.REVIEW_SUMMARY_CONFLICT);
            if (claim != null && claim.startsWith("EXISTING:")) {
                Receipt receipt = mapper.readValue(claim.substring(9), Receipt.class);
                if (!fingerprint.equals(receipt.fingerprint()) || !receipt.complete())
                    throw new CustomException(ErrorCode.REVIEW_SUMMARY_CONFLICT);
                if (receipt.bullets() == null || receipt.sourceCount() < 0)
                    throw new CustomException(ErrorCode.REVIEW_SUMMARY_UNAVAILABLE);
                return new ReviewSummaryResponse(request.query(), receipt.bullets(), receipt.sourceCount());
            }
            if (!"START".equals(claim)) throw new CustomException(ErrorCode.REVIEW_SUMMARY_UNAVAILABLE);
            ReviewSummaryResponse response = summaries.summarize(request.query());
            String done = mapper.writeValueAsString(new Receipt(fingerprint, true,
                    response.bullets(), response.sourceCount()));
            Long completed = redis.execute(COMPLETE, List.of(receiptKey, placeKey), pending, done, owner);
            if (!Long.valueOf(1).equals(completed))
                throw new CustomException(ErrorCode.REVIEW_SUMMARY_UNAVAILABLE);
            return response;
        } catch (CustomException e) {
            throw e;
        } catch (RuntimeException | JsonProcessingException e) {
            // Do not log exception bodies: upstream errors may contain queries or snippets.
            throw new CustomException(ErrorCode.REVIEW_SUMMARY_UNAVAILABLE);
        }
    }

    private record Receipt(String fingerprint, boolean complete, List<String> bullets, int sourceCount) {}

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
