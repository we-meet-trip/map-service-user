package map.service.user.vision;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;
import map.service.user.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;

class VisionPermitTest {
    @Test void requiresBothActiveAccountAndInternalCredential() {
        var redis = mock(StringRedisTemplate.class); var users = mock(UserRepository.class);
        var controller = new VisionPermitController(redis, users, "test-internal", 60);
        when(users.existsById(7L)).thenReturn(true);
        assertStatus(401, () -> controller.permit(7L, "wrong", new VisionPermitController.Request(true)));
        assertStatus(401, () -> controller.permit(null, "test-internal", new VisionPermitController.Request(true)));
        assertStatus(401, () -> controller.permit(8L, "test-internal", new VisionPermitController.Request(true)));
        verifyNoInteractions(redis);
    }
    @Test void consumesAtomicallyAndFailsClosedOnLimitOrRedisFailure() {
        var redis = mock(StringRedisTemplate.class); var users = mock(UserRepository.class);
        when(users.existsById(7L)).thenReturn(true);
        var controller = new VisionPermitController(redis, users, "test-internal", 60);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(60L);
        assertThat(controller.permit(7L, "test-internal", new VisionPermitController.Request(true)).remaining()).isZero();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-1L);
        assertStatus(429, () -> controller.permit(7L, "test-internal", new VisionPermitController.Request(true)));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("isolated Redis failure"));
        assertStatus(503, () -> controller.permit(7L, "test-internal", new VisionPermitController.Request(true)));
    }
    private void assertStatus(int expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(expected));
    }
}
