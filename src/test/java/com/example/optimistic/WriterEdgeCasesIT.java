package com.example.optimistic;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.optimistic.exception.DuplicateKeyException;
import com.example.optimistic.exception.TransientDataAccessException;
import com.example.optimistic.exception.VersionConflictException;
import com.example.paymentorders.MongoTestSupport;
import com.mongodb.MongoServerException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Updates;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC section 12.2, G12 to G18: edge cases and hostile calls of the generic writer, on a collection with no
 * validator.
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

    /** The update fails with an IllegalArgumentException caused by the server's write error, and nothing changes. */
    private void assertRejected(OptimisticLockingWriter<String> w, long expected, Bson precondition, Bson changes,
                                int code) {
        Document before = stored();
        assertThatThrownBy(() -> w.update("w", expected, precondition, changes, "bob"))
                .isInstanceOf(IllegalArgumentException.class)
                .cause().isInstanceOfSatisfying(MongoWriteException.class,
                        e -> assertThat(e.getCode()).isEqualTo(code));
        assertThat(stored()).isEqualTo(before);
    }

    @Test
    void g16_badRequestIsNotTransient() {
        insertRaw(1L);
        assertRejected(writer, 1L, null, Updates.inc("name", 1), 14);
        assertRejected(writer, 1L, null, Updates.combine(Updates.set("count", 5), Updates.inc("count", 1)), 40);
        assertRejected(writer, 1L, new Document("$noSuchOperator", 1), Updates.set("name", "b"), 2);
        widgets.drop();
        insertRaw(Long.MAX_VALUE);
        assertRejected(writer, Long.MAX_VALUE, null, Updates.set("name", "b"), 2);

        // The same bad request after a dropped connection: the error now comes from the driver's retry.
        widgets.drop();
        insertRaw(1L);
        String app = "edgeRetry";
        try (MongoClient retrying = MongoClients.create(MongoTestSupport.connectionString("appName=" + app))) {
            client.getDatabase("admin").runCommand(new Document("configureFailPoint", "failCommand")
                    .append("mode", new Document("times", 1))
                    .append("data", new Document("failCommands", List.of("update")).append("closeConnection", true)
                            .append("appName", app)));
            OptimisticLockingWriter<String> viaRetry = new OptimisticLockingWriter<>(
                    MongoTestSupport.database(retrying).getCollection("edgeWidgets"), InstantSource.fixed(NOW));
            assertRejected(viaRetry, 1L, null, Updates.inc("name", 1), 14);
        } finally {
            client.getDatabase("admin")
                    .runCommand(new Document("configureFailPoint", "failCommand").append("mode", "off"));
        }
    }

    @Test
    void g17_hostileCalls() {
        insertRaw(1L);
        Document before = stored();
        assertThatThrownBy(() -> writer.update("w", 1L, null, null, "bob"))
                .isInstanceOf(IllegalArgumentException.class);
        for (long expected : new long[] {0L, -1L}) {
            assertThatThrownBy(() -> writer.update("w", expected, null, Updates.set("name", "b"), "bob"))
                    .isInstanceOf(VersionConflictException.class);
        }
        RuntimeException boom = new RuntimeException("recompute failed");
        assertThatThrownBy(() -> writer.updateWithRetry("w", null, _ -> {
            throw boom;
        }, "job", ONE_TRY)).isSameAs(boom);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> writer.updateWithRetry("w", null, _ -> {
            calls.incrementAndGet();
            return Updates.set(WhoColumns.CREATED_BY, "eve");
        }, "job", RetryPolicy.defaults())).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(1);
        assertThat(stored()).isEqualTo(before);

        assertThatThrownBy(() -> new RetryPolicy(3, null, Duration.ZERO)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OptimisticLockingWriter<String>(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void g18_badFilterOnTheReadIsNotTransient() {
        insertRaw(1L);
        Document before = stored();
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> writer.updateWithRetry("w", new Document("$noSuchOperator", 1), _ -> {
            calls.incrementAndGet();
            return Updates.set("name", "b");
        }, "job", RetryPolicy.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .cause().isInstanceOfSatisfying(MongoServerException.class, e -> assertThat(e.getCode()).isEqualTo(2));
        assertThat(calls).hasValue(0);
        assertThat(stored()).isEqualTo(before);

        // A server error on a read that is not a bad request stays transient: here ShutdownInProgress (91) twice, so
        // that the driver's one read retry also fails.
        String app = "edgeRead";
        try (MongoClient failing = MongoClients.create(MongoTestSupport.connectionString("appName=" + app))) {
            client.getDatabase("admin").runCommand(new Document("configureFailPoint", "failCommand")
                    .append("mode", new Document("times", 2))
                    .append("data", new Document("failCommands", List.of("find")).append("errorCode", 91)
                            .append("appName", app)));
            OptimisticLockingWriter<String> viaFailing = new OptimisticLockingWriter<>(
                    MongoTestSupport.database(failing).getCollection("edgeWidgets"), InstantSource.fixed(NOW));
            assertThatThrownBy(() -> viaFailing.updateWithRetry("w", null, _ -> Updates.set("name", "b"), "job",
                    RetryPolicy.defaults()))
                    .isInstanceOfSatisfying(TransientDataAccessException.class,
                            e -> assertThat(e.outcomeUnknown()).isFalse());
        } finally {
            client.getDatabase("admin")
                    .runCommand(new Document("configureFailPoint", "failCommand").append("mode", "off"));
        }
        assertThat(stored()).isEqualTo(before);
    }
}
