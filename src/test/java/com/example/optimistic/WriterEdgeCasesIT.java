package com.example.optimistic;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.optimistic.exception.DuplicateKeyException;
import com.example.optimistic.exception.TransientDataAccessException;
import com.example.paymentorders.MongoTestSupport;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Updates;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * SPEC section 12.2, G12 to G15: edge cases of the generic writer's assumptions, on a collection with no validator.
 * The disabled tests describe the deferred classification of bad-request server errors (SPEC section 11).
 */
class WriterEdgeCasesIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final RetryPolicy ONE_TRY = new RetryPolicy(1, Duration.ZERO, Duration.ZERO);

    private static MongoClient client;
    private MongoCollection<Document> widgets;
    private OptimisticLockingWriter<String> writer;

    @BeforeAll
    static void startClient() {
        client = MongoTestSupport.newPod();
    }

    @AfterAll
    static void dropAndClose() {
        widgets(client).drop();
        client.close();
    }

    @BeforeEach
    void reset() {
        widgets = widgets(client);
        widgets.drop();
        writer = new OptimisticLockingWriter<>(widgets, InstantSource.fixed(NOW));
    }

    private static MongoCollection<Document> widgets(MongoClient client) {
        return MongoTestSupport.database(client).getCollection("edgeWidgets");
    }

    private void insertRaw(Object version) {
        widgets.insertOne(new Document("_id", "w").append("name", "a")
                .append(OptimisticLockingWriter.DOCUMENT_META,
                        new Document(OptimisticLockingWriter.VERSION_FIELD, version)));
    }

    private Document stored() {
        return widgets.find(eq("_id", "w")).first();
    }

    @Test
    void g12_badUserIdOrSystemKeyIsRejected() {
        for (String user : new String[] {null, "", " "}) {
            assertThatThrownBy(() -> writer.insert("w", new Document("name", "a"), user))
                    .as("insert, user [%s]", user).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> writer.insert(null, new Document("name", "a"), "alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.insert("w", new Document("_documentMeta.version", 99L), "alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.insert("w", new Document("_createdBy.x", "eve"), "alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(widgets.countDocuments()).isZero();

        writer.insert("w", new Document("name", "a"), "alice");
        assertThatThrownBy(() -> writer.update("w", 1L, null, Updates.set("name", "b"), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.updateWithRetry("w", null, _ -> Updates.set("name", "b"), null, ONE_TRY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(OptimisticLockingWriter.readVersion(stored())).isEqualTo(1L);
        assertThat(stored().getString("name")).isEqualTo("a");
    }

    @Test
    void g13_versionTypes() {
        for (Object whole : List.of(1, 1.0)) {
            widgets.drop();
            insertRaw(whole);
            assertThat(writer.update("w", 1L, null, Updates.set("name", "b"), "bob")).as("%s", whole).isEqualTo(2L);
            assertThat(OptimisticLockingWriter.readVersion(stored())).isEqualTo(2L);
        }
        for (Object bad : List.of(1.5, "1")) {
            widgets.drop();
            insertRaw(bad);
            assertThatThrownBy(() -> writer.update("w", 1L, null, Updates.set("name", "b"), "bob"))
                    .as("%s", bad).isInstanceOf(IllegalStateException.class);
            assertThat(stored().getString("name")).isEqualTo("a");
        }
    }

    @Test
    void g14_datesRoundTrip() {
        for (Instant t : List.of(Instant.parse("1969-07-20T20:17:40.123Z"),
                Instant.parse("9999-12-31T23:59:59.999Z"))) {
            widgets.drop();
            new OptimisticLockingWriter<String>(widgets, InstantSource.fixed(t)).insert("w", new Document(), "alice");
            assertThat(writer.read("w").get(WhoColumns.CREATED_DATE)).isEqualTo(t);
        }
        widgets.drop();
        writer.insert("w", new Document("times", List.of(NOW)).append("nested", new Document("at", NOW)), "alice");
        Document doc = writer.read("w");
        assertThat(doc.get("nested", Document.class).get("at")).isEqualTo(NOW);
        assertThat(doc.getList("times", Object.class).getFirst()).isEqualTo(Date.from(NOW)); // the known limit
    }

    @Test
    void g15_emptyChangeNumericIdsAndInterrupt() {
        writer.insert("w", new Document("name", "a"), "alice");
        assertThat(writer.update("w", 1L, null, new Document(), "bob")).isEqualTo(2L);

        OptimisticLockingWriter<Object> any = new OptimisticLockingWriter<>(widgets, InstantSource.fixed(NOW));
        any.insert(1, new Document("name", "int"), "alice");
        assertThat(any.update(1L, 1L, null, Updates.set("name", "via long"), "bob")).isEqualTo(2L);
        assertThatThrownBy(() -> any.insert(1.0, new Document(), "carol")).isInstanceOf(DuplicateKeyException.class);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> writer.update("w", 2L, null, Updates.set("name", "c"), "bob"))
                    .isInstanceOfSatisfying(TransientDataAccessException.class,
                            e -> assertThat(e.outcomeUnknown()).isTrue());
        } finally {
            Thread.interrupted();
        }
        assertThat(OptimisticLockingWriter.readVersion(stored())).isEqualTo(2L);
        assertThat(stored().getString("name")).isEqualTo("a");
    }

    // --- Deferred (SPEC section 11): a bad-request server error must not be transient ---

    private void assertNotTransient(Bson precondition, Bson changes) {
        assertThatThrownBy(() -> writer.update("w", 1L, precondition, changes, "bob"))
                .isNotInstanceOf(TransientDataAccessException.class);
    }

    @Disabled("Deferred: server code 14 is reported as transient with outcomeUnknown = true")
    @Test
    void incOnStringFieldIsNotTransient() {
        writer.insert("w", new Document("name", "a"), "alice");
        assertNotTransient(null, Updates.inc("name", 1));
    }

    @Disabled("Deferred: server code 40 is reported as transient with outcomeUnknown = true")
    @Test
    void conflictingPathsIsNotTransient() {
        writer.insert("w", new Document("count", 1), "alice");
        assertNotTransient(null, Updates.combine(Updates.set("count", 5), Updates.inc("count", 1)));
    }

    @Disabled("Deferred: server code 2 (bad filter) is reported as transient with outcomeUnknown = true")
    @Test
    void invalidPreconditionIsNotTransient() {
        writer.insert("w", new Document("name", "a"), "alice");
        assertNotTransient(new Document("$noSuchOperator", 1), Updates.set("name", "b"));
    }

    @Disabled("Deferred: server code 2 ($inc overflow) is reported as transient with outcomeUnknown = true")
    @Test
    void versionOverflowIsNotTransient() {
        insertRaw(Long.MAX_VALUE);
        assertThatThrownBy(() -> writer.update("w", Long.MAX_VALUE, null, Updates.set("name", "b"), "bob"))
                .isNotInstanceOf(TransientDataAccessException.class);
    }
}
