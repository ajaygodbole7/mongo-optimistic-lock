package com.example.optimistic;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import java.time.InstantSource;
import java.util.Arrays;
import org.bson.Document;
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
}
