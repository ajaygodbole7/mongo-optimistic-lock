package com.example.paymentorders;

import static com.example.paymentorders.MongoTestSupport.collection;
import static com.example.paymentorders.MongoTestSupport.database;
import static com.example.paymentorders.MongoTestSupport.sampleOrder;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.optimistic.OptimisticLockingWriter;
import com.example.optimistic.RetryPolicy;
import com.example.optimistic.exception.RetriesExhaustedException;
import com.example.optimistic.exception.TransientDataAccessException;
import com.example.optimistic.exception.VersionConflictException;
import com.mongodb.client.MongoClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A load harness. It runs only with {@code mvn verify -Pstress}. The parameters are system properties (refer to
 * OPERATIONS.md). A low {@code stress.orders} makes hot orders (conflicts). A high value makes a throughput test (pool,
 * latency).
 *
 * <p>Transient failures (elections, timeouts, pool exhaustion) cause the run to fail, unless
 * {@code -Dstress.allowTransient=true} is set for a run during an Atlas failover. Then the run counts them, and the
 * invariant becomes a range: successful writes &le; version &minus; 1 &le; successful + outcome-unknown writes.
 */
class StressIT {

    private static final int PODS = Integer.getInteger("stress.pods", 8);
    private static final int THREADS_PER_POD = Integer.getInteger("stress.threadsPerPod", 16);
    private static final int ORDERS = Integer.getInteger("stress.orders", 20);
    private static final int SECONDS = Integer.getInteger("stress.seconds", 20);
    private static final double SYSTEM_RATIO = Double.parseDouble(System.getProperty("stress.systemRatio", "0.2"));
    private static final String MONGO_OPTIONS = System.getProperty("stress.mongoOptions", "");
    private static final boolean ALLOW_TRANSIENT = Boolean.getBoolean("stress.allowTransient");
    private static final int WARMUP_SECONDS = Integer.getInteger("stress.warmupSeconds", 3);

    private final List<MongoClient> clients = new ArrayList<>();

    @AfterEach
    void close() {
        clients.forEach(MongoClient::close);
    }

    /** The counts of one worker thread. Workers share nothing. */
    private static final class WorkerStats {
        final long[] userWrites = new long[ORDERS];
        final long[] systemWrites = new long[ORDERS];
        final long[] userUnknown = new long[ORDERS];
        final long[] systemUnknown = new long[ORDERS];
        long transientNotApplied;
        long readFailures;
        long userConflicts;
        long systemRetries;
        long systemExhausted;
        long userMeasured;   // successful user writes inside the measured window (after warm-up)
        long systemMeasured; // successful system writes inside the measured window
        final Latencies userLatency = new Latencies();
        final Latencies systemLatency = new Latencies();
    }

    /** Latency samples of one kind of write, inside the measured window. */
    private static final class Latencies {
        long[] values = new long[1024];
        int count;

        void record(long nanos) {
            if (count == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[count++] = nanos;
        }
    }

    @Test
    void stress_noLostUpdatesUnderLoad() {
        MongoClient setupClient = MongoTestSupport.newPod(MONGO_OPTIONS);
        clients.add(setupClient);
        MongoTestSupport.resetCollection(setupClient);
        PaymentOrderService setup = new PaymentOrderService(database(setupClient));
        List<ObjectId> orders = new ArrayList<>();
        List<Document> created = new ArrayList<>();
        for (int i = 0; i < ORDERS; i++) {
            ObjectId id = setup.create(sampleOrder(), "alice");
            orders.add(id);
            created.add(collection(setupClient).find(eq("_id", id)).first());
        }

        int threads = PODS * THREADS_PER_POD;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        long[] window = new long[2]; // [start of the measured window, deadline]
        List<Future<WorkerStats>> futures = new ArrayList<>();
        long startNanos;
        long elapsedNanos;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int p = 0; p < PODS; p++) {
                MongoClient pod = MongoTestSupport.newPod(MONGO_OPTIONS);
                clients.add(pod);
                database(pod).runCommand(new Document("ping", 1));
                PaymentOrderService service = new PaymentOrderService(database(pod));
                for (int t = 0; t < THREADS_PER_POD; t++) {
                    String user = "pod-" + p + "-t" + t;
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return work(service, orders, user, window[0], window[1]);
                    }));
                }
            }
            assertThat(ready.await(60, TimeUnit.SECONDS)).isTrue();
            startNanos = System.nanoTime();
            window[0] = startNanos + TimeUnit.SECONDS.toNanos(WARMUP_SECONDS);
            window[1] = window[0] + TimeUnit.SECONDS.toNanos(SECONDS);
            start.countDown();

            List<WorkerStats> stats = new ArrayList<>();
            for (Future<WorkerStats> f : futures) {
                stats.add(f.get(WARMUP_SECONDS + SECONDS + 120L, TimeUnit.SECONDS));
            }
            elapsedNanos = window[1] - window[0];
            report(stats, elapsedNanos);
            assertThat(stats.stream().mapToLong(s -> Arrays.stream(s.userWrites).sum()
                    + Arrays.stream(s.systemWrites).sum()).sum()).as("successful writes").isPositive();
            assertThat(stats.stream().mapToLong(s -> s.userConflicts + s.systemRetries + s.systemExhausted).sum())
                    .as("conflicts: the version check was exercised").isPositive();
            assertNoLostUpdates(setupClient, orders, created, stats);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        } catch (ExecutionException e) {
            throw new AssertionError("a worker failed with an unexpected exception", e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("a worker did not finish", e);
        }
    }

    /**
     * One worker. Each round selects a random order. Then it does a user write (reload with {@code get}, amend
     * {@code amount} to the read value + 1) or a system write ({@code updateWithRetry}, also amount + 1). Thus, both
     * kinds of write are covered by the lost-update oracle.
     */
    private static WorkerStats work(PaymentOrderService service, List<ObjectId> orders, String user,
                                    long measureStart, long deadline) {
        WorkerStats stats = new WorkerStats();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Set<PaymentOrderStatus> draft = Set.of(PaymentOrderStatus.DRAFT);
        while (System.nanoTime() < deadline) {
            int idx = random.nextInt(orders.size());
            ObjectId id = orders.get(idx);
            if (random.nextDouble() < SYSTEM_RATIO) {
                int[] attempts = {0};
                long t0 = System.nanoTime();
                try {
                    service.updateWithRetry(id, draft, doc -> {
                        attempts[0]++;
                        return MongoTestSupport.incrementAmount(doc);
                    }, user, RetryPolicy.defaults());
                    stats.systemWrites[idx]++;
                    if (t0 >= measureStart) {
                        stats.systemMeasured++;
                    }
                } catch (RetriesExhaustedException e) {
                    stats.systemExhausted++;
                } catch (TransientDataAccessException e) {
                    if (!ALLOW_TRANSIENT) {
                        throw e;
                    }
                    if (e.outcomeUnknown()) {
                        stats.systemUnknown[idx]++;
                    } else {
                        stats.transientNotApplied++;
                    }
                }
                if (t0 >= measureStart) {
                    stats.systemLatency.record(System.nanoTime() - t0);
                }
                stats.systemRetries += Math.max(0, attempts[0] - 1);
            } else {
                Document current;
                try {
                    current = service.get(id).orElseThrow(); // the reload that callers must use (majority read)
                } catch (TransientDataAccessException e) {
                    if (!ALLOW_TRANSIENT) {
                        throw e;
                    }
                    stats.readFailures++;
                    continue;
                }
                long version = OptimisticLockingWriter.readVersion(current);
                BigDecimal next = MongoTestSupport.amountOf(current).add(BigDecimal.ONE);
                long t0 = System.nanoTime();
                try {
                    service.amend(id, version, Map.of("amount", next), user);
                    stats.userWrites[idx]++;
                    if (t0 >= measureStart) {
                        stats.userMeasured++;
                    }
                } catch (VersionConflictException e) {
                    stats.userConflicts++; // the caller reloads on its next round
                } catch (TransientDataAccessException e) {
                    if (!ALLOW_TRANSIENT) {
                        throw e;
                    }
                    if (e.outcomeUnknown()) {
                        stats.userUnknown[idx]++;
                    } else {
                        stats.transientNotApplied++;
                    }
                }
                if (t0 >= measureStart) {
                    stats.userLatency.record(System.nanoTime() - t0);
                }
            }
        }
        return stats;
    }

    private static void report(List<WorkerStats> stats, long measuredNanos) {
        long userWrites = stats.stream().mapToLong(s -> s.userMeasured).sum();
        long systemWrites = stats.stream().mapToLong(s -> s.systemMeasured).sum();
        long conflicts = stats.stream().mapToLong(s -> s.userConflicts).sum();
        long allUserWrites = stats.stream().mapToLong(s -> Arrays.stream(s.userWrites).sum()).sum();
        long retries = stats.stream().mapToLong(s -> s.systemRetries).sum();
        long exhausted = stats.stream().mapToLong(s -> s.systemExhausted).sum();
        long notApplied = stats.stream().mapToLong(s -> s.transientNotApplied).sum();
        long unknown = stats.stream().mapToLong(s -> Arrays.stream(s.userUnknown).sum()
                + Arrays.stream(s.systemUnknown).sum()).sum();
        long readFailures = stats.stream().mapToLong(s -> s.readFailures).sum();
        long[] user = sorted(stats, true);
        long[] system = sorted(stats, false);
        double seconds = measuredNanos / 1e9;

        System.out.printf("""

                === Stress: %d pods x %d threads, %d orders, %d s warm-up + %.1f s measured, system ratio %.2f,
                    options '%s', allowTransient %b
                writes ok      : %d (%,.0f/s, measured window)  user %d, system %d
                user conflicts : %d (%.1f%% of user attempts, whole run)
                system retries : %d, exhausted %d (whole run)
                transient      : %d not applied, %d outcome unknown; %d failed reloads
                user latency   : p50 %.2f ms, p99 %.2f ms, max %.2f ms (one amend)
                system latency : p50 %.2f ms, p99 %.2f ms, max %.2f ms (whole updateWithRetry, with waits)
                %n""",
                PODS, THREADS_PER_POD, ORDERS, WARMUP_SECONDS, seconds, SYSTEM_RATIO, MONGO_OPTIONS, ALLOW_TRANSIENT,
                userWrites + systemWrites, (userWrites + systemWrites) / seconds, userWrites, systemWrites,
                conflicts, conflicts * 100.0 / Math.max(1, allUserWrites + conflicts),
                retries, exhausted,
                notApplied, unknown, readFailures,
                percentileMillis(user, 0.50), percentileMillis(user, 0.99), percentileMillis(user, 1.0),
                percentileMillis(system, 0.50), percentileMillis(system, 0.99), percentileMillis(system, 1.0));
    }

    private static long[] sorted(List<WorkerStats> stats, boolean user) {
        return stats.stream()
                .map(s -> user ? s.userLatency : s.systemLatency)
                .flatMapToLong(l -> Arrays.stream(l.values, 0, l.count))
                .sorted().toArray();
    }

    private static double percentileMillis(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1);
        return sorted[Math.max(0, index)] / 1e6;
    }

    private static void assertNoLostUpdates(MongoClient client, List<ObjectId> orders, List<Document> created,
                                            List<WorkerStats> stats) {
        for (int i = 0; i < orders.size(); i++) {
            int idx = i;
            long user = stats.stream().mapToLong(s -> s.userWrites[idx]).sum();
            long system = stats.stream().mapToLong(s -> s.systemWrites[idx]).sum();
            long userUnknown = stats.stream().mapToLong(s -> s.userUnknown[idx]).sum();
            long systemUnknown = stats.stream().mapToLong(s -> s.systemUnknown[idx]).sum();
            Document doc = collection(client).find(eq("_id", orders.get(i))).first();

            // Exact when nothing was "outcome unknown"; otherwise each unknown write may or may not have applied.
            assertThat(OptimisticLockingWriter.readVersion(doc) - 1).as("order %d version", i)
                    .isBetween(user + system, user + system + userUnknown + systemUnknown);
            // Both kinds of write add 1 to amount from the value that they read: a lost update loses an increment.
            BigDecimal applied = MongoTestSupport.amountOf(doc).subtract(new BigDecimal("1250.00"));
            assertThat(applied).as("order %d amount - 1250", i).isBetween(BigDecimal.valueOf(user + system),
                    BigDecimal.valueOf(user + system + userUnknown + systemUnknown));
            assertThat(doc.get("_createdDate", Instant.class))
                    .isEqualTo(created.get(i).get("_createdDate", Instant.class));
            assertThat(doc.getString("_createdBy")).isEqualTo(created.get(i).getString("_createdBy"));
        }
    }
}
