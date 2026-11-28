package com.example.optimistic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import java.time.InstantSource;
import com.mongodb.client.model.Updates;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** G21: the version path is checked when the writer is built. No database call: the client never connects. */
class OptimisticLockingWriterTest {

    private static MongoClient client;
    private static MongoCollection<Document> collection;

    @BeforeAll
    static void unconnectedCollection() {
        client = MongoClients.create("mongodb://localhost:1");
        collection = client.getDatabase("unused").getCollection("unused");
    }

    @AfterAll
    static void close() {
        client.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "a..b", ".a", "a.", "$v", "a.$v", "items.0", "_id", "_id.v", "_createdBy",
            "_lastModifiedDate.v"})
    void g21_badVersionPathIsRejected(String path) {
        assertThatThrownBy(() -> new OptimisticLockingWriter<>(collection, InstantSource.system(), path))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void g21_goodVersionPathIsAccepted() {
        for (String path : Arrays.asList("version", "meta.rev")) {
            assertThatCode(() -> new OptimisticLockingWriter<>(collection, InstantSource.system(), path))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * G28: each public method rejects a null argument with IllegalArgumentException before any database call. The
     * client never connects, so a call that reached the database would time out with another exception.
     */
    @Test
    void g28_nullArgumentsAreRejectedBeforeAnyDatabaseCall() {
        OptimisticLockingWriter<String> writer = new OptimisticLockingWriter<>(collection);
        Document fields = new Document("name", "a");
        Document nullKey = new Document();
        nullKey.put(null, "a");
        AtomicInteger recomputes = new AtomicInteger();
        java.util.function.Function<Document, org.bson.conversions.Bson> recompute = _ -> {
            recomputes.incrementAndGet();
            return Updates.set("name", "b");
        };
        RetryPolicy policy = RetryPolicy.defaults();

        Map<String, ThrowingCallable> calls = new LinkedHashMap<>();
        calls.put("new(collection=null)", () -> new OptimisticLockingWriter<String>(null));
        calls.put("new(collection=null, clock)", () -> new OptimisticLockingWriter<String>(null, InstantSource.system()));
        calls.put("new(clock=null)", () -> new OptimisticLockingWriter<String>(collection, null));
        calls.put("new(versionPath=null)", () -> new OptimisticLockingWriter<String>(collection, InstantSource.system(), null));
        calls.put("insert(id=null)", () -> writer.insert(null, fields, "alice"));
        calls.put("insert(fields=null)", () -> writer.insert("w", null, "alice"));
        calls.put("insert(fields has a null key)", () -> writer.insert("w", nullKey, "alice"));
        calls.put("insert(user=null)", () -> writer.insert("w", fields, null));
        calls.put("update(id=null)", () -> writer.update(null, 1L, null, Updates.set("name", "b"), "bob"));
        calls.put("update(changes=null)", () -> writer.update("w", 1L, null, null, "bob"));
        calls.put("update(user=null)", () -> writer.update("w", 1L, null, Updates.set("name", "b"), null));
        calls.put("updateWithRetry(id=null)", () -> writer.updateWithRetry(null, null, recompute, "job", policy));
        calls.put("updateWithRetry(recompute=null)", () -> writer.updateWithRetry("w", null, null, "job", policy));
        calls.put("updateWithRetry(user=null)", () -> writer.updateWithRetry("w", null, recompute, null, policy));
        calls.put("updateWithRetry(policy=null)", () -> writer.updateWithRetry("w", null, recompute, "job", null));
        calls.put("read(id=null)", () -> writer.read(null));
        calls.put("readVersion(doc=null)", () -> writer.readVersion(null));
        calls.put("withInstantDates(codecs=null)", () -> OptimisticLockingWriter.withInstantDates(null));

        assertSoftly(soft -> calls.forEach((name, call) ->
                soft.assertThatThrownBy(call).as(name).isInstanceOf(IllegalArgumentException.class)));
        assertThat(recomputes).as("recompute never ran").hasValue(0);
    }

    /** G29: a stored version that is not a usable number is an IllegalStateException, never another exception. */
    @Test
    void g29_aVersionThatIsNotAWholeNumberIsAnIllegalState() {
        OptimisticLockingWriter<String> writer = new OptimisticLockingWriter<>(collection);
        java.util.List<Object> bad = java.util.List.of(Decimal128.NaN, Decimal128.POSITIVE_INFINITY,
                Decimal128.NEGATIVE_INFINITY, new Decimal128(new java.math.BigDecimal("1e30")),
                new Decimal128(java.math.BigDecimal.valueOf(Long.MAX_VALUE).add(java.math.BigDecimal.ONE)), Double.NaN, Double.POSITIVE_INFINITY, 1.5, 1e30, 9.223372036854775807E18, "7", true);

        for (Object version : bad) {
            Document doc = new Document("_id", "w").append("_documentMeta", new Document("version", version));
            assertThatThrownBy(() -> writer.readVersion(doc)).as("version %s", version)
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
