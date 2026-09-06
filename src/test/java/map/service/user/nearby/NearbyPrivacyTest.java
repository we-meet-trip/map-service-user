package map.service.user.nearby;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.time.LocalDate;
import java.util.UUID;
import map.service.user.schedule.ScheduleService;
import map.service.user.schedule.ScheduleEntity;
import org.junit.jupiter.api.Test;

class NearbyPrivacyTest {
    @Test void holdUsesDecryptedOwnedScheduleWithoutRecordingImpressionsOrClicks() throws Exception {
        ScheduleService schedules = mock(ScheduleService.class);
        HubNearbyClient hub = mock(HubNearbyClient.class);
        NearbyImpressionRepository signals = mock(NearbyImpressionRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ScheduleEntity schedule = new ScheduleEntity(7L, UUID.randomUUID(), "trip", LocalDate.now(),
                LocalDate.now(), mapper.readTree("{\"encrypted\":true}"), "walk", 9, 18);
        when(schedules.requireOwned(1L, 7L)).thenReturn(schedule);
        when(schedules.readPayload(schedule)).thenReturn(mapper.readTree(
                "{\"places\":[{\"place_id\":1,\"day\":1,\"lat\":37.5,\"lng\":127}],\"visit_order\":[1]}"));
        when(hub.find(37.5, 127, "food", 1000, 10)).thenReturn(List.of(
                new NearbyPlace("real", "place", "address", 37.5, 127.0, "food", 0, null)));
        NearbyService service = new NearbyService(schedules, hub, signals, 1000, 10);
        assertThat(service.find(1L, 7L, 1, 1, "food")).hasSize(1);
        assertThat(service.recordClick(1L, 7L, 1, 1, "food", "real")).isFalse();
        verifyNoInteractions(signals);
        verify(schedules).readPayload(schedule);
    }
}
