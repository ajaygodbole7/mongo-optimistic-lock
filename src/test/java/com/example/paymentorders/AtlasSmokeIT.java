package com.example.paymentorders;

import static com.example.paymentorders.MongoTestSupport.collection;
import static com.example.paymentorders.MongoTestSupport.database;
import static com.example.paymentorders.MongoTestSupport.sampleOrder;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.optimistic.OptimisticLockingWriter;
import com.example.optimistic.exception.VersionConflictException;
import com.example.paymentorders.exception.InvalidStatusException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Some end-to-end checks against a real cluster: {@code MONGO_URI=... mvn verify -Patlas}. Without
 * {@code MONGO_URI}, the profile fails immediately. With only {@code -Dit.test=AtlasSmokeIT}, it uses the local
 * container. It uses no fail points, so it works on Atlas.
 */
class AtlasSmokeIT {

    private static MongoClient client;
    private static PaymentOrderService service;

    @BeforeAll
    static void connect() {
        System.out.println("Atlas smoke target: " + MongoTestSupport.describeTarget());
        client = MongoTestSupport.newPod();
        MongoTestSupport.resetCollection(client);
        service = new PaymentOrderService(database(client));
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    private static Map<String, Object> remittance(String text) {
        return Map.of("remittance", new NewPaymentOrder.Remittance("INV-4471", text));
    }

    @Test
    void a1_connectedToAWritablePrimaryOfAReplicaSet() {
        Document hello = client.getDatabase("admin").runCommand(new Document("hello", 1));
        Document buildInfo = client.getDatabase("admin").runCommand(new Document("buildInfo", 1));
        System.out.printf("Atlas smoke: replica set %s, %d hosts, server %s%n", hello.getString("setName"),
                hello.getList("hosts", String.class, List.of()).size(), buildInfo.getString("version"));

        assertThat(hello.getBoolean("isWritablePrimary")).isTrue();
        assertThat(hello.getString("setName")).as("a replica set, as on Atlas").isNotBlank();
    }

    @Test
    void a2_lifecycleWithConflictsAndStatusChecks() {
        ObjectId id = service.create(sampleOrder(), "smoke");

        assertThat(service.amend(id, 1L, remittance("first"), "smoke")).isEqualTo(2L);
        assertThatThrownBy(() -> service.amend(id, 1L, remittance("stale"), "smoke"))
                .isInstanceOf(VersionConflictException.class);
        assertThat(service.submit(id, 2L, "smoke")).isEqualTo(3L);
        assertThatThrownBy(() -> service.amend(id, 3L, remittance("late"), "smoke"))
                .isInstanceOf(InvalidStatusException.class);
        assertThat(service.cancel(id, 3L, "smoke")).isEqualTo(4L);
    }

    @Test
    void a3_serverValidatorIsActive() {
        Instant now = Instant.parse("2026-11-02T08:00:00Z");
        Document missingCreatedBy = new Document("status", "DRAFT")
                .append("_documentMeta", new Document("version", 1L))
                .append("_createdDate", now).append("_lastModifiedDate", now).append("_lastModifiedBy", "smoke");

        assertThatThrownBy(() -> collection(client).insertOne(missingCreatedBy))
                .isInstanceOfSatisfying(MongoWriteException.class, e -> assertThat(e.getCode()).isEqualTo(121));
    }

    @Test
    void a4_concurrentPodsSameVersionExactlyOneWins() {
        ObjectId id = service.create(sampleOrder(), "smoke");
        int podCount = 8;
        List<MongoClient> pods = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < podCount; i++) {
                MongoClient pod = MongoTestSupport.newPod();
                pods.add(pod);
                pod.getDatabase("admin").runCommand(new Document("ping", 1));
                PaymentOrderService podService = new PaymentOrderService(database(pod));
                String user = "smoke-pod-" + i;
                results.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        podService.amend(id, 1L, remittance(user), user);
                        return true;
                    } catch (VersionConflictException e) {
                        return false;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }, executor));
            }
            start.countDown();

            long winners = results.stream().filter(CompletableFuture::join).count();
            assertThat(winners).isEqualTo(1);
            Document doc = collection(client).find(eq("_id", id)).first();
            assertThat(OptimisticLockingWriter.readVersion(doc)).isEqualTo(2L);
        } finally {
            pods.forEach(MongoClient::close);
        }
    }
}
