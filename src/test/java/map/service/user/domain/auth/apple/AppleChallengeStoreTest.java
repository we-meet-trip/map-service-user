package map.service.user.domain.auth.apple;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

class AppleChallengeStoreTest {
    @Test void challengeHasSingleUseStateAndRedisFailureFailsClosed() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        AppleChallengeStore store = new AppleChallengeStore(redis);
        var issued = store.issue();
        assertThat(issued.get("state")).matches("[A-Za-z0-9_-]{43}");
        assertThat(issued.get("nonce")).matches("[A-Za-z0-9_-]{43}");
        when(values.getAndDelete(anyString())).thenReturn(issued.get("nonce"),null);
        assertThat(store.consume(issued.get("state"))).isEqualTo(issued.get("nonce"));
        assertThatThrownBy(() -> store.consume(issued.get("state"))).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(401));
        when(values.getAndDelete(anyString())).thenThrow(new RuntimeException("isolated Redis failure"));
        assertThatThrownBy(() -> store.consume(issued.get("state"))).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(503));
    }
}
