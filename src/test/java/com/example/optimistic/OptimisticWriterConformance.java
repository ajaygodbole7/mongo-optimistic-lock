package com.example.optimistic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.optimistic.exception.VersionConflictException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Updates;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks that a collection works with {@link OptimisticLockingWriter}. A service team extends this class in its own
 * tests (the {@code test-jar} of this project has it) and gives four things:
 * <ol>
 *   <li>{@link #collection()}: the collection, on a test database. A replica set, as in production.</li>
 *   <li>{@link #newId()}: a new, unique {@code _id}.</li>
 *   <li>{@link #validFields()}: business fields that the collection's validator accepts.</li>
 *   <li>{@link #validChange(int)}: an update that the validator accepts, with a different value for each marker.</li>
 * </ol>
 * The tests write only new documents, with new ids. They do not drop or change other documents.
 *
 * @param <K> the type of {@code _id}
 */
public abstract class OptimisticWriterConformance<K> {

    private static final String USER = "conformance";

    protected abstract MongoCollection<Document> collection();

    protected abstract K newId();

    protected abstract Document validFields();

    protected abstract Bson validChange(int marker);

    private OptimisticLockingWriter<K> writer;

    @BeforeEach
    void createWriter() {
        writer = new OptimisticLockingWriter<>(collection());
    }

    @Test
    void c1_insertSetsVersion1AndAllFourWhoColumns() {
        K id = newId();

        writer.insert(id, validFields(), USER);

        Document doc = writer.read(id);
        assertThat(writer.readVersion(doc)).isEqualTo(1L);
        assertThat(doc.getString(WhoColumns.CREATED_BY)).isEqualTo(USER);
        assertThat(doc.getString(WhoColumns.LAST_MODIFIED_BY)).isEqualTo(USER);
        assertThat(doc.get(WhoColumns.CREATED_DATE, Instant.class)).isNotNull();
        assertThat(doc.get(WhoColumns.LAST_MODIFIED_DATE, Instant.class)).isNotNull();
    }

    @Test
    void c2_aStaleVersionIsAConflictAndChangesNothing() {
        K id = newId();
        writer.insert(id, validFields(), USER);
        writer.update(id, 1L, null, validChange(1), USER);

        assertThatThrownBy(() -> writer.update(id, 1L, null, validChange(2), USER))
                .isInstanceOfSatisfying(VersionConflictException.class, e -> {
                    assertThat(e.expectedVersion()).isEqualTo(1L);
                    assertThat(e.actualVersion()).isEqualTo(2L);
                });
        assertThat(writer.readVersion(writer.read(id))).isEqualTo(2L);
    }

    @Test
    void c3_concurrentWritersConflictAndEveryRetryAfterAReloadSucceeds() throws ExecutionException, InterruptedException {
        K id = newId();
        writer.insert(id, validFields(), USER);
        int threads = 16;
        int writesEach = 5;
        AtomicInteger conflicts = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(executor.submit(() -> {
                    for (int w = 0; w < writesEach; w++) {
                        applyWithReload(id, thread * writesEach + w, conflicts);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        // Each success moved the version by one. Conflicts show that the version check ran: without it, no writer
        // would ever conflict, and the writers would overwrite each other.
        assertThat(writer.readVersion(writer.read(id))).isEqualTo(1L + threads * writesEach);
        assertThat(conflicts.get()).as("conflicts").isPositive();
    }

    /** What a caller does after a conflict: reload, then write again with the new version. */
    private void applyWithReload(K id, int marker, AtomicInteger conflicts) {
        while (true) {
            long version = writer.readVersion(writer.read(id));
            try {
                writer.update(id, version, null, validChange(marker), USER);
                return;
            } catch (VersionConflictException conflict) {
                conflicts.incrementAndGet(); // another writer was first: reload
            }
        }
    }

    @Test
    void c4_theCreatedPairNeverChangesAfterAnUpdate() {
        K id = newId();
        writer.insert(id, validFields(), USER);
        Document before = writer.read(id);

        writer.update(id, 1L, null, validChange(1), "someone-else");

        Document after = writer.read(id);
        assertThat(after.getString(WhoColumns.CREATED_BY)).isEqualTo(before.getString(WhoColumns.CREATED_BY));
        assertThat(after.get(WhoColumns.CREATED_DATE, Instant.class))
                .isEqualTo(before.get(WhoColumns.CREATED_DATE, Instant.class));
        assertThat(after.getString(WhoColumns.LAST_MODIFIED_BY)).isEqualTo("someone-else");
    }

    @Test
    void c5_aChangeToASystemFieldIsRejectedAndChangesNothing() {
        K id = newId();
        writer.insert(id, validFields(), USER);

        for (String field : new String[] {WhoColumns.CREATED_BY, WhoColumns.LAST_MODIFIED_BY, "_id",
                OptimisticLockingWriter.DOCUMENT_META}) {
            assertThatThrownBy(() -> writer.update(id, 1L, null, Updates.set(field, "x"), USER))
                    .as("change to %s", field).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(writer.readVersion(writer.read(id))).isEqualTo(1L);
    }
}
