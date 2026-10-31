package com.example.paymentorders;

import static com.example.paymentorders.MongoTestSupport.collection;
import static com.example.paymentorders.MongoTestSupport.database;
import static com.example.paymentorders.MongoTestSupport.sampleOrder;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.optimistic.OptimisticLockingWriter;
import com.example.optimistic.RetryPolicy;
import com.example.optimistic.exception.RetriesExhaustedException;
import com.example.optimistic.exception.VersionConflictException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Each simulated pod has its own MongoClient and service. The pods share only the database. */
class ConcurrencyIT {

    private MongoClient setupClient;
    private PaymentOrderService setup;
    private final List<MongoClient> pods = new ArrayList<>();

    @BeforeEach
    void reset() {
        setupClient = MongoTestSupport.newPod();
        MongoTestSupport.resetCollection(setupClient);
        setup = new PaymentOrderService(database(setupClient));
    }

    @AfterEach
    void close() {
        pods.forEach(MongoClient::close);
        setupClient.close();
    }

    private Document load(ObjectId id) {
        return collection(setupClient).find(eq("_id", id)).first();
    }

    @FunctionalInterface
    private interface PodTask<T> {
        Callable<T> create(int podIndex, MongoClient pod);
    }

    /** What one pod's task gave: a value, or the exception that it threw. */
    private sealed interface Outcome<T> {
        record Won<T>(T value) implements Outcome<T> {
        }

        record Lost<T>(Throwable error) implements Outcome<T> {
        }
    }

    private static <T> List<T> wins(List<Outcome<T>> outcomes) {
        return outcomes.stream().<T>mapMulti((outcome, wins) -> {
            if (outcome instanceof Outcome.Won<T>(T value)) {
                wins.accept(value);
            }
        }).toList();
    }

    private static <T> List<Throwable> losses(List<Outcome<T>> outcomes) {
        return outcomes.stream().<Throwable>mapMulti((outcome, losses) -> {
            if (outcome instanceof Outcome.Lost<T>(Throwable error)) {
                losses.accept(error);
            }
        }).toList();
    }

    /** Starts {@code count} pods, releases their tasks at the same time, and returns all results in pod order. */
    private <T> List<Outcome<T>> runPods(int count, PodTask<T> task) {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MongoClient pod = MongoTestSupport.newPod();
            pods.add(pod);
            database(pod).runCommand(new Document("ping", 1)); // open a connection before the race
            tasks.add(task.create(i, pod));
        }

        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return t.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> f : futures) {
                try {
                    outcomes.add(new Outcome.Won<>(f.get(120, TimeUnit.SECONDS)));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome.Lost<>(e.getCause()));
                } catch (TimeoutException e) {
                    throw new AssertionError("pod did not finish", e);
                }
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while running pods", e);
        }
    }

    @Test
    void t8_sixteenPodsSameVersion() {
        ObjectId id = setup.create(sampleOrder(), "alice");

        List<Outcome<Long>> outcomes = runPods(16, (i, pod) -> {
            PaymentOrderService service = new PaymentOrderService(database(pod));
            return () -> service.amend(id, 1L,
                    Map.of("remittance", new NewPaymentOrder.Remittance("INV-4471", "pod-" + i)), "pod-" + i);
        });

        assertThat(outcomes).hasSize(16);
        assertThat(wins(outcomes)).containsExactly(2L);
        assertThat(losses(outcomes)).hasSize(15).allSatisfy(error -> assertThat(error)
                .isInstanceOfSatisfying(VersionConflictException.class,
                        e -> assertThat(e.actualVersion()).isEqualTo(2L)));

        Document doc = load(id);
        assertThat(OptimisticLockingWriter.readVersion(doc)).isEqualTo(2L);
        assertThat(doc.getString("_lastModifiedBy"))
                .isEqualTo(doc.getEmbedded(List.of("remittance", "unstructured"), String.class));
    }

    @Test
    void t9_submitVsCancel() {
        ObjectId id = setup.create(sampleOrder(), "alice");
        List<String> resultingStatus = List.of("SUBMITTED", "CANCELLED");

        List<Outcome<Long>> outcomes = runPods(2, (i, pod) -> {
            PaymentOrderService service = new PaymentOrderService(database(pod));
            return i == 0
                    ? () -> service.submit(id, 1L, "submitter")
                    : () -> service.cancel(id, 1L, "canceller");
        });

        assertThat(outcomes).hasSize(2);
        assertThat(wins(outcomes)).containsExactly(2L);
        assertThat(losses(outcomes)).singleElement().isInstanceOf(VersionConflictException.class);
        int winner = outcomes.getFirst() instanceof Outcome.Won<Long> ? 0 : 1;

        Document doc = load(id);
        assertThat(doc.getString("status")).isEqualTo(resultingStatus.get(winner));
        assertThat(OptimisticLockingWriter.readVersion(doc)).isEqualTo(2L);
    }

    @Test
    void t13_differentSubtreesSameVersionStillConflict() {
        ObjectId id = setup.create(sampleOrder(), "alice");
        Document before = load(id);
        var munich = new NewPaymentOrder.Address("2 Isar Weg", "Munich", "80331", "DE");
        var bobsRemittance = new NewPaymentOrder.Remittance("INV-4471", "Bob's note");

        List<Outcome<Long>> outcomes = runPods(2, (i, pod) -> {
            PaymentOrderService service = new PaymentOrderService(database(pod));
            return i == 0
                    ? () -> service.updateCreditorAddress(id, 1L, munich, "alice")
                    : () -> service.amend(id, 1L, Map.of("remittance", bobsRemittance), "bob");
        });

        // The version guards the whole document: disjoint fields don't let both writers through.
        assertThat(outcomes).hasSize(2);
        assertThat(wins(outcomes)).containsExactly(2L);
        assertThat(losses(outcomes)).singleElement().isInstanceOfSatisfying(VersionConflictException.class,
                e -> assertThat(e.actualVersion()).isEqualTo(2L));
        int winner = outcomes.getFirst() instanceof Outcome.Won<Long> ? 0 : 1;

        Document doc = load(id);
        assertThat(OptimisticLockingWriter.readVersion(doc)).isEqualTo(2L);
        Object address = doc.getEmbedded(List.of("creditor", "address"), Document.class);
        Object remittance = doc.get("remittance");
        if (winner == 0) {
            assertThat(doc.getString("_lastModifiedBy")).isEqualTo("alice");
            assertThat(doc.getEmbedded(List.of("creditor", "address", "city"), String.class)).isEqualTo("Munich");
            assertThat(remittance).as("Bob's change not applied").isEqualTo(before.get("remittance"));
        } else {
            assertThat(doc.getString("_lastModifiedBy")).isEqualTo("bob");
            assertThat(doc.getEmbedded(List.of("remittance", "unstructured"), String.class)).isEqualTo("Bob's note");
            assertThat(address).as("Alice's change not applied")
                    .isEqualTo(before.getEmbedded(List.of("creditor", "address"), Document.class));
        }
    }

    /**
     * The merge check (CONFLICTS.md, "Merge"): after a conflict, the caller compares each field that it changed with
     * the base value that it read. The writer has no merge; the check belongs to the caller.
     */
    private static boolean unchanged(Document base, Document current, List<String> changedPath) {
        return Objects.equals(base.getEmbedded(changedPath, Object.class),
                current.getEmbedded(changedPath, Object.class));
    }

    /** A service on a new pod (its own MongoClient), closed after the test. */
    private PaymentOrderService podService() {
        MongoClient pod = MongoTestSupport.newPod();
        pods.add(pod);
        return new PaymentOrderService(database(pod));
    }

    @Test
    void t13b_mergeAfterTheCheckKeepsBothChanges() {
        ObjectId id = setup.create(sampleOrder(), "alice");
        PaymentOrderService alice = podService();
        PaymentOrderService bob = podService();
        Document base = alice.get(id).orElseThrow(); // what Alice read, at version 1
        var munich = new NewPaymentOrder.Address("2 Isar Weg", "Munich", "80331", "DE");

        // Bob changes a different field first.
        bob.amend(id, 1L, Map.of("remittance", new NewPaymentOrder.Remittance("INV-4471", "Bob's note")), "bob");
        assertThatThrownBy(() -> alice.updateCreditorAddress(id, 1L, munich, "alice"))
                .isInstanceOf(VersionConflictException.class);

        // Alice reloads, checks her field, and sends her change with the new version.
        Document current = alice.get(id).orElseThrow();
        assertThat(unchanged(base, current, List.of("creditor", "address"))).isTrue();
        long merged = alice.updateCreditorAddress(id, OptimisticLockingWriter.readVersion(current), munich, "alice");

        assertThat(merged).isEqualTo(3L);
        Document doc = load(id);
        assertThat(doc.getEmbedded(List.of("creditor", "address", "city"), String.class)).isEqualTo("Munich");
        assertThat(doc.getEmbedded(List.of("remittance", "unstructured"), String.class)).isEqualTo("Bob's note");
        assertThat(doc.getString("_lastModifiedBy")).isEqualTo("alice");
    }

    @Test
    void t14_systemRetryOnHotOrder() {
        int podCount = 16;
        int writesPerPod = 20;
        ObjectId id = setup.create(sampleOrder(), "alice");
        RetryPolicy policy = RetryPolicy.defaults();

        // Each pod returns {successes, exhausted, recompute calls}.
        List<Outcome<long[]>> outcomes = runPods(podCount, (pod, client) -> {
            PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(client));
            return () -> {
                long[] counts = new long[3];
                for (int i = 0; i < writesPerPod; i++) {
                    try {
                        writer.updateWithRetry(id, Set.of(PaymentOrderStatus.DRAFT), doc -> {
                            counts[2]++;
                            return MongoTestSupport.incrementAmount(doc);
                        }, "system", policy);
                        counts[0]++;
                    } catch (RetriesExhaustedException e) {
                        counts[1]++; // a job would requeue this work
                    }
                }
                return counts;
            };
        });

        assertThat(losses(outcomes)).isEmpty();
        List<long[]> counts = wins(outcomes);
        assertThat(counts).hasSize(podCount).allSatisfy(c -> assertThat(c[0] + c[1]).isEqualTo(writesPerPod));
        long successes = counts.stream().mapToLong(c -> c[0]).sum();
        long recomputes = counts.stream().mapToLong(c -> c[2]).sum();
        assertThat(successes).isPositive();
        assertThat(recomputes).as("contention made the retry path run").isGreaterThan(successes);

        Document doc = load(id);
        assertThat(OptimisticLockingWriter.readVersion(doc) - 1).isEqualTo(successes);
        assertThat(MongoTestSupport.amountOf(doc)).isEqualByComparingTo(
                new BigDecimal("1250.00").add(BigDecimal.valueOf(successes)));
    }

    @Test
    void t10_noLostUpdates() {
        int podCount = 8;
        int rounds = 50;
        List<ObjectId> orders = new ArrayList<>();
        List<Document> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ObjectId id = setup.create(sampleOrder(), "alice");
            orders.add(id);
            created.add(load(id));
        }

        // Read-modify-write: each write sets amount to the value it read + 1, so a write that overwrote another
        // (a lost update) would lose an increment. All pods work on the same order in the same round.
        // Each pod returns its successful writes per order, then its conflict count; pods share no state.
        List<Outcome<long[]>> outcomes = runPods(podCount, (pod, client) -> {
            PaymentOrderService service = new PaymentOrderService(database(client));
            return () -> {
                long[] counts = new long[orders.size() + 1];
                for (int round = 0; round < rounds; round++) {
                    int idx = round % orders.size();
                    ObjectId id = orders.get(idx);
                    while (true) {
                        Document current = service.get(id).orElseThrow(); // majority read, as callers must
                        long version = OptimisticLockingWriter.readVersion(current);
                        BigDecimal next = MongoTestSupport.amountOf(current).add(BigDecimal.ONE);
                        try {
                            service.amend(id, version, Map.of("amount", next), "pod-" + pod);
                            counts[idx]++;
                            break;
                        } catch (VersionConflictException e) {
                            counts[orders.size()]++; // the caller decides: reload and try again
                        }
                    }
                }
                return counts;
            };
        });

        assertThat(losses(outcomes)).isEmpty();
        long[] totals = new long[orders.size() + 1];
        for (long[] counts : wins(outcomes)) {
            for (int i = 0; i < totals.length; i++) {
                totals[i] += counts[i];
            }
        }
        assertThat(Arrays.stream(totals, 0, orders.size()).sum()).isEqualTo((long) podCount * rounds);
        assertThat(totals[orders.size()]).as("conflicts: the version check was exercised").isPositive();

        for (int i = 0; i < orders.size(); i++) {
            Document doc = load(orders.get(i));
            assertThat(OptimisticLockingWriter.readVersion(doc) - 1).as("order %d version", i).isEqualTo(totals[i]);
            assertThat(MongoTestSupport.amountOf(doc)).as("order %d amount", i)
                    .isEqualByComparingTo(new BigDecimal("1250.00").add(BigDecimal.valueOf(totals[i])));
            assertThat(doc.get("_createdDate", Instant.class))
                    .isEqualTo(created.get(i).get("_createdDate", Instant.class));
            assertThat(doc.getString("_createdBy")).isEqualTo(created.get(i).getString("_createdBy"));
        }
    }
}
