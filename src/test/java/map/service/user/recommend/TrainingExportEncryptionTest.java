package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.crypto.PayloadCipher;
import map.service.user.global.crypto.TestPayloadCiphers;
import map.service.user.nearby.NearbyImpressionRepository;
import map.service.user.schedule.ScheduleArrivalEntity;
import map.service.user.schedule.ScheduleArrivalRepository;
import map.service.user.schedule.ScheduleEntity;
import map.service.user.schedule.ScheduleExportRepository;
import map.service.user.schedule.ScheduleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Pure synthetic objects and mocked read repositories: no Spring boot, DB, Redis or files. */
@ExtendWith(OutputCaptureExtension.class)
class TrainingExportEncryptionTest {
    private static final long OWNER = 7L;
    private static final long SCHEDULE = 42L;
    private static final UUID SAVED = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PARENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String PRIVATE_MARKER = "fixture-location-token-email-must-not-be-logged";
    private final ObjectMapper mapper = new ObjectMapper();
    private final PayloadCipher cipher = TestPayloadCiphers.enabled();

    private ObjectNode itinerary() {
        ObjectNode node = mapper.createObjectNode();
        node.put("fixture_private_value", PRIVATE_MARKER);
        var places = node.putArray("places");
        places.addObject().put("place_id", 0).put("day", 1).put("content_id", "place:a");
        places.addObject().put("place_id", 1).put("day", 1).put("content_id", "place:b");
        node.putArray("visit_order").add(1).add(0);
        return node;
    }

    private ObjectNode rejected() {
        ObjectNode node = mapper.createObjectNode();
        node.put("fixture_private_value", PRIVATE_MARKER);
        node.putArray("places").addObject().put("content_id", "place:rejected");
        return node;
    }

    private ObjectNode encryptedSchedule() {
        return (ObjectNode) cipher.encryptNode(itinerary(),
                PayloadCipher.aad("schedules", "payload", Long.toString(OWNER)));
    }

    private ObjectNode encryptedParent() {
        return (ObjectNode) cipher.encryptNode(rejected(),
                PayloadCipher.aad("recommend_jobs", "result_payload", PARENT.toString()));
    }

    private final class Fixture {
        private final ScheduleExportRepository schedules = mock(ScheduleExportRepository.class);
        private final ScheduleArrivalRepository arrivals = mock(ScheduleArrivalRepository.class);
        private final NearbyImpressionRepository impressions = mock(NearbyImpressionRepository.class);
        private final RecommendJobRepository jobs = mock(RecommendJobRepository.class);
        private final RecommendTrainingRepository training = mock(RecommendTrainingRepository.class);
        private final UserRepository users = mock(UserRepository.class);
        private final TrainingExportService service;
        private final ScheduleEntity schedule;
        private final RecommendJobEntity parent;

        private Fixture(JsonNode storedSchedule, JsonNode storedParent) {
            schedule = new ScheduleEntity(OWNER, SAVED, "synthetic", LocalDate.of(2026, 9, 6),
                    LocalDate.of(2026, 9, 6), storedSchedule, "walk", 9, 18);
            ReflectionTestUtils.setField(schedule, "scheduleId", SCHEDULE);
            RecommendJobEntity saved = new RecommendJobEntity(SAVED, null, "done", null, null, null);
            ReflectionTestUtils.setField(saved, "mode", "research");
            ReflectionTestUtils.setField(saved, "parentJobId", PARENT);
            parent = new RecommendJobEntity(PARENT, null, "done", storedParent, null, null);
            Map<UUID, RecommendJobEntity> byId = Map.of(SAVED, saved, PARENT, parent);
            when(jobs.findAllById(any())).thenAnswer(invocation -> {
                Iterable<UUID> ids = invocation.getArgument(0);
                List<RecommendJobEntity> found = new ArrayList<>();
                ids.forEach(id -> { if (byId.containsKey(id)) found.add(byId.get(id)); });
                return found;
            });
            when(jobs.findById(PARENT)).thenReturn(java.util.Optional.of(parent));
            when(schedules.findPage(0L, 500)).thenReturn(List.of(schedule));
            ObjectNode signal = mapper.createObjectNode().put("schema_version", 2).put("path", "select");
            signal.putArray("chosen_content_ids").add("place:a");
            var candidates = signal.putArray("candidates");
            candidates.addObject().put("rank", 0).put("content_id", "place:a");
            candidates.addObject().put("rank", 1).put("content_id", "place:b");
            when(training.findAllById(any())).thenReturn(List.of(new RecommendTrainingEntity(SAVED, 2, signal)));
            when(arrivals.findByScheduleIdIn(any())).thenReturn(List.of(new ScheduleArrivalEntity(
                    SCHEDULE, 1, 1, OffsetDateTime.parse("2026-09-06T10:00:00Z"), "ok")));
            when(impressions.findByScheduleIdIn(any())).thenReturn(List.of());
            // Only readPayload is invoked. These real codecs share the serving AAD contract.
            ScheduleService scheduleReader = new ScheduleService(null, null, null, null, null,
                    mapper, null, cipher, null, null);
            RecommendJobStore jobReader = new RecommendJobStore(jobs, training, null, mapper,
                    cipher, null, null, users);
            service = new TrainingExportService(schedules, arrivals, impressions, jobs, training,
                    users, new RecommendCacheKey(50000, 60), mapper, scheduleReader, jobReader, 500);
        }

        private TrainingExportService.Result export() {
            // Explicit synthetic unit fixture, not a batch startup or actual data export.
            return service.export(false, List.of(), "synthetic-test-salt");
        }

        private void assertReadOnly() {
            for (Object repository : List.of(schedules, arrivals, impressions, jobs, training, users)) {
                assertThat(mockingDetails(repository).getInvocations())
                        .allSatisfy(invocation -> assertThat(invocation.getMethod().getName()).startsWith("find"));
            }
        }
    }

    @Test
    void encryptedAndLegacyPlaintextProduceSameSavedArrivalAndResearchLabelsWithoutWrites(CapturedOutput output) {
        Fixture plain = new Fixture(itinerary(), rejected());
        ObjectNode storedSchedule = encryptedSchedule();
        ObjectNode storedParent = encryptedParent();
        JsonNode beforeSchedule = storedSchedule.deepCopy();
        JsonNode beforeParent = storedParent.deepCopy();
        Fixture encrypted = new Fixture(storedSchedule, storedParent);

        var expected = plain.export().rows().get(0);
        var actual = encrypted.export().rows().get(0);

        assertThat(actual.labels()).isEqualTo(expected.labels());
        assertThat(actual.candidates()).isEqualTo(expected.candidates());
        assertThat(actual.l1Eligible()).isTrue();
        assertThat(actual.labels().savedContentIds()).containsExactly("place:a", "place:b");
        assertThat(actual.labels().arrivedContentIds()).containsExactly("place:b");
        assertThat(actual.labels().rejectedContentIds()).containsExactly("place:rejected");
        assertThat(actual.labels().rejectedSource()).isEqualTo("parent_result_payload");
        assertThat(encrypted.schedule.getPayload()).isEqualTo(beforeSchedule);
        assertThat(encrypted.parent.getResultPayload()).isEqualTo(beforeParent);
        plain.assertReadOnly();
        encrypted.assertReadOnly();
        assertThat(output.getAll()).doesNotContain(PRIVATE_MARKER);
    }

    private void assertClosed(JsonNode schedule, JsonNode parent, CapturedOutput output) {
        Fixture fixture = new Fixture(schedule, parent);
        assertThatThrownBy(fixture::export).isInstanceOf(IllegalStateException.class)
                .hasMessage("training export stored payload unreadable").hasNoCause();
        fixture.assertReadOnly();
        assertThat(output.getAll()).doesNotContain(PRIVATE_MARKER);
    }

    @Test
    void tamperedScheduleDoesNotBecomeSuccessfulEmptyLabels(CapturedOutput output) {
        ObjectNode broken = encryptedSchedule().put("ct", PRIVATE_MARKER);
        assertClosed(broken, encryptedParent(), output);
    }

    @Test
    void missingScheduleKeyFailsWithoutLeakingKeyIdentifier(CapturedOutput output) {
        ObjectNode broken = encryptedSchedule().put("kid", PRIVATE_MARKER);
        assertClosed(broken, encryptedParent(), output);
    }

    @Test
    void scheduleEncryptedForAnotherOwnerFails(CapturedOutput output) {
        JsonNode wrongOwner = cipher.encryptNode(itinerary(), PayloadCipher.aad("schedules", "payload", "99"));
        assertClosed(wrongOwner, encryptedParent(), output);
    }

    @Test
    void incompleteEnvelopeCannotMasqueradeAsLegacyPlaintext(CapturedOutput output) {
        ObjectNode broken = encryptedSchedule();
        broken.remove("ct");
        assertClosed(broken, encryptedParent(), output);
    }

    @Test
    void corruptedParentDoesNotBecomeUnavailableRejection(CapturedOutput output) {
        assertClosed(encryptedSchedule(), encryptedParent().put("ct", PRIVATE_MARKER), output);
    }

    @Test
    void parentEncryptedWithSavedJobAadFails(CapturedOutput output) {
        JsonNode wrongJob = cipher.encryptNode(rejected(),
                PayloadCipher.aad("recommend_jobs", "result_payload", SAVED.toString()));
        assertClosed(encryptedSchedule(), wrongJob, output);
    }

    @Test
    void nullLegacyParentRetainsUnavailableSemantics() {
        Fixture fixture = new Fixture(itinerary(), null);
        var row = fixture.export().rows().get(0);
        assertThat(row.labels().savedContentIds()).containsExactly("place:a", "place:b");
        assertThat(row.labels().rejectedContentIds()).isEmpty();
        assertThat(row.labels().rejectedSource()).isEqualTo("unavailable");
        fixture.assertReadOnly();
    }
}
