package com.example.optimistic;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.optimistic.exception.DocumentNotFoundException;
import com.example.optimistic.exception.DuplicateKeyException;
import com.example.optimistic.exception.PreconditionFailedException;
import com.example.optimistic.exception.RetriesExhaustedException;
import com.example.optimistic.exception.SchemaValidationException;
import com.example.optimistic.exception.TransientDataAccessException;
import com.example.optimistic.exception.VersionConflictException;
import com.example.paymentorders.MongoTestSupport;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Updates;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandSucceededEvent;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC section 12.2 (G1 to G9): the generic writer alone. The collection {@code widgets} has string ids and no
 * validator, and no payment-order type is used.
 */
class OptimisticLockingWriterIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

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
        return MongoTestSupport.database(client).getCollection("widgets");
    }

    private Document load(String id) {
        return widgets.find(eq("_id", id)).first();
    }

    private static long version(Document doc) {
        return OptimisticLockingWriter.readVersion(doc);
    }

    @Test
    void g1_insertWithCallerIdThenDuplicate() {
        writer.insert("w-1", new Document("name", "first").append("count", 0), "alice");

        Document doc = load("w-1");
        assertThat(version(doc)).isEqualTo(1L);
        assertThat(doc.get("_createdDate", Instant.class)).isEqualTo(NOW);
        assertThat(doc.get("_lastModifiedDate", Instant.class)).isEqualTo(NOW);
        assertThat(doc.getString("_createdBy")).isEqualTo("alice");
        assertThat(doc.getString("_lastModifiedBy")).isEqualTo("alice");

        assertThatThrownBy(() -> writer.insert("w-1", new Document("name", "second"), "bob"))
                .isInstanceOfSatisfying(DuplicateKeyException.class, e -> assertThat(e.id()).isEqualTo("w-1"))
                .isNotInstanceOf(TransientDataAccessException.class);
        assertThat(widgets.countDocuments()).isEqualTo(1);
        assertThat(load("w-1").getString("name")).isEqualTo("first");

        // A client with the driver's default codecs (dates as java.util.Date): the writer still reads Instants.
        try (MongoClient plain = MongoClients.create(MongoTestSupport.connectionString(""))) {
            Document read = new OptimisticLockingWriter<String>(widgets(plain)).read("w-1");
            assertThat(read.get("_createdDate")).isEqualTo(NOW);
            assertThat(read.get("_lastModifiedDate")).isEqualTo(NOW);
        }
    }

    @Test
    void g2_updateCurrentThenStaleVersion() {
        writer.insert("w-2", new Document("name", "a"), "alice");

        BsonDocument changes = BsonDocument.parse("{\"$set\": {\"name\": \"b\"}}");
        assertThat(writer.update("w-2", 1L, null, changes, "bob")).isEqualTo(2L);
        assertThat(changes).as("the caller's object is not changed")
                .isEqualTo(BsonDocument.parse("{\"$set\": {\"name\": \"b\"}}"));

        assertThatThrownBy(() -> writer.update("w-2", 1L, null, Updates.set("name", "c"), "carol"))
                .isInstanceOfSatisfying(VersionConflictException.class, e -> {
                    assertThat(e.expectedVersion()).isEqualTo(1L);
                    assertThat(e.actualVersion()).isEqualTo(2L);
                });
        assertThat(load("w-2").getString("name")).isEqualTo("b");
    }

    @Test
    void g3_guardNotMetIsPreconditionFailed() {
        writer.insert("w-3", new Document("count", 2), "alice");
        Bson atLeastFive = gte("count", 5);

        assertThatThrownBy(() -> writer.update("w-3", 1L, atLeastFive, Updates.inc("count", -5), "bob"))
                .isInstanceOfSatisfying(PreconditionFailedException.class,
                        e -> assertThat(e.current().getInteger("count")).isEqualTo(2));
        assertThatThrownBy(() -> writer.updateWithRetry("w-3", atLeastFive, doc -> {
            throw new AssertionError("recompute must not run");
        }, "job", RetryPolicy.defaults()))
                .isExactlyInstanceOf(PreconditionFailedException.class);
        assertThat(version(load("w-3"))).isEqualTo(1L);
    }

    @Test
    void g4_missingIdIsNotFound() {
        assertThatThrownBy(() -> writer.update("nope", 1L, null, Updates.set("name", "x"), "bob"))
                .isInstanceOfSatisfying(DocumentNotFoundException.class, e -> assertThat(e.id()).isEqualTo("nope"));
    }

    /** Each thread is a pod with its own client. The counter is calculated from the read, so a lost update shows. */
    @Test
    void g5_concurrentRecomputeLosesNoUpdate() throws Exception {
        writer.insert("w-5", new Document("count", 0), "seed");
        int threads = 8;
        int rounds = 25;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<int[]>> futures = new ArrayList<>();
        int successes = 0;
        int exhausted = 0;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    int[] outcome = new int[2]; // successes, exhausted
                    try (MongoClient pod = MongoTestSupport.newPod()) {
                        var podWriter = new OptimisticLockingWriter<String>(widgets(pod));
                        start.await();
                        for (int i = 0; i < rounds; i++) {
                            try {
                                podWriter.updateWithRetry("w-5", null,
                                        doc -> Updates.set("count", doc.getInteger("count") + 1), "job",
                                        RetryPolicy.defaults());
                                outcome[0]++;
                            } catch (RetriesExhaustedException _) {
                                outcome[1]++;
                            }
                        }
                    }
                    return outcome;
                }));
            }
            start.countDown();
            for (Future<int[]> future : futures) {
                int[] outcome = future.get(2, TimeUnit.MINUTES);
                successes += outcome[0];
                exhausted += outcome[1];
            }
        }

        Document doc = load("w-5");
        assertThat(successes + exhausted).isEqualTo(threads * rounds);
        assertThat(doc.getInteger("count")).isEqualTo(successes);
        assertThat(version(doc) - 1).isEqualTo(successes);
    }

    @Test
    void g6_systemFieldOrRenameIsRejected() {
        writer.insert("w-6", new Document("name", "a"), "alice");
        Document before = load("w-6");

        List<Bson> invalid = List.of(
                Updates.set("_documentMeta.version", 9L),
                Updates.set("_createdBy", "mallory"),
                Updates.unset("_lastModifiedDate"),
                Updates.set("_id", "w-7"),
                new Document("$rename", new Document("name", "title")));
        for (var changes : invalid) {
            assertThatThrownBy(() -> writer.update("w-6", 1L, null, changes, "bob"))
                    .as("%s", changes)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> writer.insert("w-8", new Document("_createdBy", "mallory"), "bob"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(load("w-6")).isEqualTo(before);
        assertThat(widgets.countDocuments()).isEqualTo(1);
    }

    @Test
    void g7_shardKeyLikePrecondition() {
        writer.insert("w-7", new Document("tenant", "t1").append("name", "a"), "alice");

        assertThat(writer.update("w-7", 1L, tenant("t1"), Updates.set("name", "b"), "bob")).isEqualTo(2L);
        assertThatThrownBy(() -> writer.update("w-7", 2L, tenant("t2"), Updates.set("name", "c"), "bob"))
                .isInstanceOf(PreconditionFailedException.class);
        assertThat(load("w-7").getString("name")).isEqualTo("b");
    }

    private static Bson tenant(String tenant) {
        return eq("tenant", tenant);
    }

    @Test
    void g8_recomputeNullWritesNothing() {
        writer.insert("w-8", new Document("name", "a"), "alice");
        Document before = load("w-8");

        long version = writer.updateWithRetry("w-8", null, doc -> null, "job", RetryPolicy.defaults());

        assertThat(version).isEqualTo(1L);
        assertThat(load("w-8")).isEqualTo(before);
    }

    /**
     * The update matches nothing because the precondition is false. Then, before the diagnosis read, a different
     * writer makes it true again without a version change (as after a rollback). The listener runs on the calling
     * thread, after the update and before the diagnosis read, so the order is certain.
     */
    @Test
    void g9_preconditionTrueAgainAtDiagnosisIsAConflict() {
        writer.insert("w-9", new Document("state", "closed"), "alice");
        AtomicBoolean reopened = new AtomicBoolean();
        CommandListener reopenAfterZeroMatch = new CommandListener() {
            @Override
            public void commandSucceeded(CommandSucceededEvent event) {
                if (event.getCommandName().equals("update") && event.getResponse().getNumber("n").intValue() == 0
                        && reopened.compareAndSet(false, true)) {
                    widgets.updateOne(eq("_id", "w-9"), Updates.set("state", "open")); // bypasses the writer
                }
            }
        };
        try (MongoClient listened = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(MongoTestSupport.connectionString("")))
                .addCommandListener(reopenAfterZeroMatch).build())) {
            var raced = new OptimisticLockingWriter<String>(widgets(listened));

            assertThatThrownBy(() -> raced.update("w-9", 1L, eq("state", "open"), Updates.set("name", "x"), "bob"))
                    .isExactlyInstanceOf(VersionConflictException.class);
        }
        assertThat(reopened).isTrue();
    }

    /** The update itself is small enough to send, but the server refuses the document that it would make. */
    @Test
    void g10_updateThatGrowsTheDocumentPastTheLimitIsNotValid() {
        String nineMegabytes = "x".repeat(9 * 1024 * 1024);
        writer.insert("w-10", new Document("a", nineMegabytes), "alice");

        assertThatThrownBy(() -> writer.update("w-10", 1L, null, Updates.set("b", nineMegabytes), "bob"))
                .isInstanceOfSatisfying(SchemaValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo(SchemaValidationException.Reason.TOO_LARGE))
                .hasMessageContaining("is larger than the 16 MB BSON document limit")
                .cause().isInstanceOfSatisfying(MongoWriteException.class,
                        cause -> assertThat(cause.getCode()).isEqualTo(10334));
        assertThat(version(load("w-10"))).isEqualTo(1L);
    }
}
