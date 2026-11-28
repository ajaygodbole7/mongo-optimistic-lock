package com.example.optimistic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.paymentorders.PaymentOrderClients;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mongodb.MongoDBContainer;

/** What the writer does on a standalone mongod (no replica set). Production runs on a replica set. */
class StandaloneIT {

    private static MongoDBContainer mongo;
    private static MongoClient client;
    private static MongoCollection<Document> widgets;

    @BeforeAll
    static void startStandalone() {
        mongo = new MongoDBContainer("mongo:9.0.4"); // no withReplicaSet(): a standalone server
        mongo.start();
        client = PaymentOrderClients.create(mongo.getConnectionString());
        widgets = client.getDatabase("standalone").getCollection("widgets");
    }

    @AfterAll
    static void stop() {
        client.close();
        mongo.stop();
    }

    @Test
    void g25_aStandaloneServerIsRefusedAtStartup() {
        assertThatThrownBy(() -> OptimisticLockingWriter.requireReplicaSet(client.getDatabase("standalone")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("standalone");
    }

    /** The reason for the check: without it, the writer runs on a standalone server and reports no problem. */
    @Test
    void g25_withoutTheCheckTheWriterWorksOnStandaloneWithoutAnyError() {
        OptimisticLockingWriter<String> writer = new OptimisticLockingWriter<>(widgets);

        writer.insert("w", new Document("name", "a"), "alice");

        assertThat(writer.update("w", 1L, null, Updates.set("name", "b"), "bob")).isEqualTo(2L);
        assertThat(writer.read("w").getString("name")).isEqualTo("b");
    }
}
