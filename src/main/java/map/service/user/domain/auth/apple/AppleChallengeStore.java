package map.service.user.domain.auth.apple;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import map.service.user.domain.auth.service.AuthService;

@Component
public class AppleChallengeStore {
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    public AppleChallengeStore(@Qualifier("rateLimitRedisTemplate") StringRedisTemplate redis) { this.redis=redis; }
    public Map<String,String> issue() {
        String state=random(), nonce=random();
        try { redis.opsForValue().set(key(state),nonce,Duration.ofMinutes(5)); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Apple challenge unavailable"); }
        return Map.of("state",state,"nonce",nonce);
    }
    public String consume(String state) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Apple state");
        String nonce;
        try { nonce=redis.opsForValue().getAndDelete(key(state)); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Apple challenge unavailable"); }
        if (nonce==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Expired or reused Apple state");
        return nonce;
    }
    private String key(String state) { return "auth:apple:nonce:"+AuthService.sha256Hex(state); }
    private String random() { byte[] value=new byte[32];random.nextBytes(value);return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
}
