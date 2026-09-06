package map.service.user.recommend;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import map.service.user.global.crypto.TestPayloadCiphers;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/** Real encrypted persistence in an isolated H2 transaction; no Redis or external client. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RecommendEditDurabilityTest {
    @Autowired RecommendCancellationRepository cancellations;
    @Autowired RecommendJobRepository jobs;
    @Autowired RecommendEditRepository edits;
    @Autowired RecommendTrainingRepository training;
    @Autowired RecommendEditRequestRepository receipts;
    @Autowired TestEntityManager em;
    RecommendJobStore store;

    @BeforeEach void setup() {
        store = new RecommendJobStore(jobs, training, edits, new ObjectMapper(),
                TestPayloadCiphers.enabled(), receipts, cancellations, TestJobOwners.active());
    }
    String owned(long uid) {
        String id = UUID.randomUUID().toString();
        store.insertInProgress(id, null, RecommendJobStore.JobOrigin.agent("init").ownedBy(uid));
        store.recordCompletion(id, "done", "{\"v\":0}", "{\"schema_version\":1,\"path\":\"route\"}");
        em.flush(); em.clear();
        return id;
    }
    @Test void captureHoldStillPersistsEditsAndEncryptedRetryReceipt() {
        String id = owned(7);
        store.persistOwnedEdit(id, 7L, "request", "patch", before -> "{\"v\":1}");
        em.flush(); em.clear();
        assertThat(store.findFinishedPayload(id)).contains("{\"v\":1}");
        assertThat(training.count()).isZero();
        assertThat(edits.count()).isZero();
        assertThat(receipts.count()).isEqualTo(1);
        assertThat(receipts.findAll().get(0).getResponsePayload().has("ct")).isTrue();
    }
    @Test void retryReturnsOriginalResponseWithoutUndoingLaterEdit() {
        String id = owned(7);
        store.persistOwnedEdit(id, 7L, "first", "patch1", before -> "{\"v\":1}");
        store.persistOwnedEdit(id, 7L, "second", "patch2", before -> "{\"v\":2}");
        var replay = store.persistOwnedEdit(id, 7L, "first", "patch1", before -> {
            throw new AssertionError("A replay must not revalidate or apply the old patch");
        });
        assertThat(replay.response()).isEqualTo("{\"v\":1}");
        assertThat(replay.canonical()).isEqualTo("{\"v\":2}");
        assertThat(store.findFinishedPayload(id)).contains("{\"v\":2}");
    }
    @Test void conflictingKeyIsRejectedAndSameKeyInAnotherJobIsIndependent() {
        String id = owned(7);
        String other = owned(7);
        store.persistOwnedEdit(id, 7L, "key", "a", before -> "{\"v\":1}");
        assertThatThrownBy(() -> store.persistOwnedEdit(id, 7L, "key", "b", before -> "{}"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RECOMMEND_EDIT_CONFLICT);
        assertThat(store.persistOwnedEdit(other, 7L, "key", "b", before -> "{\"v\":3}").response())
                .isEqualTo("{\"v\":3}");
    }
    @Test void missingAnonymousAndForeignOwnersCannotReadOrEdit() {
        String id = owned(7);
        for (Long uid : new Long[] { null, 8L }) {
            assertThatThrownBy(() -> store.requireOwned(id, uid)).isInstanceOf(CustomException.class);
            assertThatThrownBy(() -> store.persistOwnedEdit(id, uid, null, "{}", before -> "{}"))
                    .isInstanceOf(CustomException.class);
        }
        assertThatThrownBy(() -> store.requireOwned(UUID.randomUUID().toString(), 7L))
                .isInstanceOf(CustomException.class);
        String anonymous = UUID.randomUUID().toString();
        store.insertInProgress(anonymous, null);
        assertThatThrownBy(() -> store.requireOwned(anonymous, 7L)).isInstanceOf(CustomException.class);
    }
    @Test void completionRedeliveryPreservesAcceptedEdit() {
        String id = owned(7);
        store.persistOwnedEdit(id, 7L, null, "patch", before -> "{\"v\":1}");
        store.recordCompletion(id, "done", "{\"v\":0}", null);
        em.flush(); em.clear();
        assertThat(store.findFinishedPayload(id)).contains("{\"v\":1}");
    }
    @Test void expiredReceiptIsSweptAndRetryWindowMatchesDraftSetting() {
        String id = owned(7);
        org.springframework.test.util.ReflectionTestUtils.setField(store, "editReceiptTtlSeconds", -1L);
        store.persistOwnedEdit(id, 7L, "old", "patch", before -> "{\"v\":1}");
        store.expireEditReceipts();
        em.flush(); em.clear();
        assertThat(receipts.count()).isZero();
        assertThat(store.findFinishedPayload(id)).contains("{\"v\":1}");
    }
    @Test void erasureRemovesResultsAndReceiptsAndSuppressesLateCompletion() {
        String id = owned(7);
        store.persistOwnedEdit(id, 7L, "request", "patch", before -> "{\"v\":1}");
        store.eraseOwnedJobs(7L);
        em.flush(); em.clear();
        store.recordCompletion(id, "done", "{\"v\":99}", "{\"schema_version\":1,\"path\":\"route\"}");
        em.flush(); em.clear();
        assertThat(store.findFinishedPayload(id)).isEmpty();
        assertThat(store.isCancelled(id)).isTrue();
        assertThat(receipts.count()).isZero();
        assertThat(training.count()).isZero();
        assertThatThrownBy(() -> store.requireOwned(id, 7L)).isInstanceOf(CustomException.class);
    }

    @Test void erasureRemovesLegacyActorCopyWithoutDeletingAnotherOwnersJobOrEdit() {
        String other = owned(8);
        var payload = new ObjectMapper().createObjectNode().put("legacy", true);
        edits.save(new RecommendEditEntity(UUID.fromString(other), 1, 7L, "legacy-personal", payload, payload));
        edits.save(new RecommendEditEntity(UUID.fromString(other), 2, 8L, "legacy-other", payload, payload));
        em.flush(); em.clear();
        store.eraseOwnedJobs(7L);
        em.flush(); em.clear();
        assertThat(edits.findAll()).extracting(RecommendEditEntity::getActorUserId).containsExactly(8L);
        assertThat(store.findFinishedPayload(other)).isPresent();
        assertThat(store.isCancelled(other)).isFalse();
    }

}
