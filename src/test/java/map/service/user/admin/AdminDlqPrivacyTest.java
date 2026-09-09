package map.service.user.admin;

import map.service.user.global.crypto.PayloadCipher;
import map.service.user.recommend.DraftStore;
import map.service.user.recommend.RecommendJobStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdminDlqPrivacyTest {
    @Test
    @SuppressWarnings("unchecked")
    void list_preserves_action_identity_without_reading_or_exposing_payload() {
        var cipher = mock(PayloadCipher.class);
        var service = new AdminDlqService(mock(RedisConnectionFactory.class),
                mock(DraftStore.class), mock(RecommendJobStore.class), "synthetic-dlq", cipher);
        var template = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
        when(template.opsForStream()).thenReturn(stream);
        ReflectionTestUtils.setField(service, "streamsTemplate", template);
        var record = MapRecord.create("synthetic-dlq", Map.<Object, Object>of(
                "job_id", "11111111-1111-1111-1111-111111111111", "status", "failed",
                "delivery_count", "4", "error", "max retries exceeded (4 deliveries)",
                "payload", "synthetic-private-location-and-secret")).withId(RecordId.of("1234-0"));
        when(stream.reverseRange(eq("synthetic-dlq"), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(record));
        var result = service.list(10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).recordId()).isEqualTo("1234-0");
        assertThat(result.get(0).jobId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(result.get(0).deliveryCount()).isEqualTo("4");
        assertThat(result.get(0).error()).isEqualTo("delivery_retry_exhausted");
        assertThat(result.get(0).payloadPreview()).isEqualTo("[비공개]");
        assertThat(result.toString()).doesNotContain("synthetic-private-location-and-secret");
        verifyNoInteractions(cipher);
        verify(stream, never()).delete(anyString(), anyString());
    }
}
