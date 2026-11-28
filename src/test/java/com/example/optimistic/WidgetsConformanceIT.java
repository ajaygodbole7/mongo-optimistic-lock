package com.example.optimistic;

import com.example.paymentorders.MongoTestSupport;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Updates;
import java.util.UUID;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** Runs the conformance tests on a plain collection with string ids: the example for service teams. */
class WidgetsConformanceIT extends OptimisticWriterConformance<String> {

    private static MongoClient client;

    @BeforeAll
    static void connect() {
        client = MongoTestSupport.newPod();
    }

    @AfterAll
    static void close() {
        client.close();
    }

    @Override
    protected MongoCollection<Document> collection() {
        return MongoTestSupport.database(client).getCollection("conformance");
    }

    @Override
    protected String newId() {
        return UUID.randomUUID().toString();
    }

    @Override
    protected Document validFields() {
        return new Document("name", "first");
    }

    @Override
    protected Bson validChange(int marker) {
        return Updates.set("name", "change-" + marker);
    }
}
