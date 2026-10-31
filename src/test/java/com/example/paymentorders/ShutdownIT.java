package com.example.paymentorders;

import static com.example.paymentorders.MongoTestSupport.collection;
import static com.example.paymentorders.MongoTestSupport.database;
import static com.example.paymentorders.MongoTestSupport.sampleOrder;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.optimistic.OptimisticLockingWriter;
import com.example.optimistic.RetryPolicy;
import com.example.paymentorders.exception.ShuttingDownException;
import com.mongodb.client.MongoClient;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Graceful shutdown of {@link PaymentOrderRuntime}. The fail points apply only to the appName of this class. */
class ShutdownIT {

    private static final String APP = "shutdown-it";

    private MongoClient setupClient;
    private ObjectId id;
    private final AtomicInteger closes = new AtomicInteger();
    private PaymentOrderRuntime runtime;

    @BeforeEach
    void reset() {
        setupClient = MongoTestSupport.newPod();
        MongoTestSupport.resetCollection(setupClient);
        id = new PaymentOrderService(database(setupClient)).create(sampleOrder(), "alice");
        runtime = new PaymentOrderRuntime(countingCloses(MongoTestSupport.newPod("appName=" + APP)),
                MongoTestSupport.DB);
    }

    @AfterEach
    void disarmAndClose() {
        try {
            if (setupClient != null) {
                setupClient.getDatabase("admin")
                        .runCommand(new Document("configureFailPoint", "failCommand").append("mode", "off"));
            }
        } finally {
            if (runtime != null) {
                runtime.shutdown(Duration.ZERO);
            }
            if (setupClient != null) {
                setupClient.close();
            }
        }
    }

    /** Wraps a real client, so that the test can count the {@code close()} calls. */
    private MongoClient countingCloses(MongoClient real) {
        return (MongoClient) Proxy.newProxyInstance(MongoClient.class.getClassLoader(),
                new Class<?>[] {MongoClient.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        closes.incrementAndGet();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private void blockUpdates(int blockTimeMs) {
        setupClient.getDatabase("admin").runCommand(new Document("configureFailPoint", "failCommand")
                .append("mode", new Document("times", 1))
                .append("data", new Document("failCommands", List.of("update")).append("appName", APP)
                        .append("blockConnection", true).append("blockTimeMS", blockTimeMs)));
    }

    /** Waits until the server runs the update of this class. Then the update is in flight. */
    private void awaitUpdateInFlight() {
        Document busy = new Document("currentOp", 1).append("appName", APP)
                .append("command.update", PaymentOrderSchema.COLLECTION);
        for (int i = 0; i < 250; i++) {
            if (!setupClient.getDatabase("admin").runCommand(busy).getList("inprog", Document.class).isEmpty()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("update never reached the server");
    }

    /** Waits until shutdown closed the gate. Then the gate refuses a new call. */
    private void awaitGateClosed() {
        for (int i = 0; i < 500; i++) {
            try {
                runtime.service().get(id);
            } catch (ShuttingDownException e) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("shutdown never closed the gate");
    }

    private CompletableFuture<Long> amendInBackground(String text) {
        return CompletableFuture.supplyAsync(() -> runtime.service().amend(id, 1L,
                Map.of("remittance", new NewPaymentOrder.Remittance("INV-4471", text)), "bob"),
                MongoTestSupport.VIRTUAL_THREADS);
    }

    private long version() {
        return OptimisticLockingWriter.readVersion(collection(setupClient).find(eq("_id", id)).first());
    }

    @Test
    void s1_inFlightCallFinishesBeforeTheClientCloses() throws Exception {
        blockUpdates(500);
        CompletableFuture<Long> amend = amendInBackground("s1");
        awaitUpdateInFlight();

        runtime.shutdown(Duration.ofSeconds(5));

        // The call finished before shutdown returned. The future completes a moment after the gate counts the call
        // out, so wait briefly for it, not for the call.
        assertThat(amend.get(1, TimeUnit.SECONDS)).isEqualTo(2L);
        assertThat(closes).hasValue(1);
        assertThat(version()).isEqualTo(2L);
    }

    @Test
    void s2_callsAfterShutdownAreRefusedWithoutTouchingTheDatabase() {
        runtime.shutdown(Duration.ofSeconds(1));
        PaymentOrderService service = runtime.service();
        var address = new NewPaymentOrder.Address("2 Isar Weg", "Munich", "80331", "DE");

        List<ThrowingCallable> calls = List.of(
                () -> service.create(sampleOrder(), "bob"),
                () -> service.amend(id, 1L, Map.of("currency", "USD"), "bob"),
                () -> service.updateCreditorAddress(id, 1L, address, "bob"),
                () -> service.submit(id, 1L, "bob"),
                () -> service.cancel(id, 1L, "bob"),
                () -> service.get(id),
                () -> service.updateWithRetry(id, java.util.Set.of(PaymentOrderStatus.DRAFT),
                        MongoTestSupport::incrementAmount, "system", RetryPolicy.defaults()));
        for (ThrowingCallable call : calls) {
            assertThatThrownBy(call).isInstanceOf(ShuttingDownException.class);
        }
        assertThat(collection(setupClient).countDocuments()).isEqualTo(1);
        assertThat(version()).isEqualTo(1L);
    }

    /**
     * S3: the grace period ends. Shutdown closes the client and returns. In driver 5.13, close() does not stop an
     * operation that already uses a connection. The held call finishes independently (here, it is successful when the
     * server's block ends), within timeoutMS, and reports its result normally. Thus, the termination grace period of
     * the pod must be more than the preStop sleep plus the shutdown grace plus timeoutMS (OPERATIONS.md).
     */
    @Test
    void s3_graceRunsOutThenTheClientClosesAndTheHeldCallFinishesOnItsOwn() throws Exception {
        blockUpdates(3000);
        CompletableFuture<Long> amend = amendInBackground("s3");
        awaitUpdateInFlight();

        long start = System.nanoTime();
        runtime.shutdown(Duration.ofMillis(500));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).as("returned after about the grace period").isBetween(450L, 2500L);
        assertThat(closes).hasValue(1);
        assertThat(amend).as("not aborted by close()").isNotDone();

        long result = amend.get(PaymentOrderClients.DEFAULT_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS);
        assertThat(result).isEqualTo(2L);
        assertThat(version()).isEqualTo(2L);
    }

    @Test
    void s4_shutdownTwiceAndConcurrentlyClosesTheClientOnce() throws Exception {
        runtime.shutdown(Duration.ofSeconds(1));
        runtime.shutdown(Duration.ofSeconds(1));
        assertThat(closes).hasValue(1);

        closes.set(0);
        PaymentOrderRuntime other = new PaymentOrderRuntime(countingCloses(MongoTestSupport.newPod("appName=" + APP)),
                MongoTestSupport.DB);
        CountDownLatch start = new CountDownLatch(1);
        Runnable shutdown = () -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            other.shutdown(Duration.ofSeconds(1));
        };
        CompletableFuture<Void> first = CompletableFuture.runAsync(shutdown, MongoTestSupport.VIRTUAL_THREADS);
        CompletableFuture<Void> second = CompletableFuture.runAsync(shutdown, MongoTestSupport.VIRTUAL_THREADS);
        start.countDown();
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);

        assertThat(closes).hasValue(1);
    }

    /** S4: a second call during the drain of the first call waits until the client is closed. */
    @Test
    void s4_secondCallWaitsUntilTheFirstIsComplete() throws Exception {
        blockUpdates(1000);
        CompletableFuture<Long> amend = amendInBackground("s4");
        awaitUpdateInFlight();

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> runtime.shutdown(Duration.ofSeconds(5)),
                MongoTestSupport.VIRTUAL_THREADS);
        awaitGateClosed();
        runtime.shutdown(Duration.ofSeconds(5));

        assertThat(closes).as("the second call returned only after the close").hasValue(1);
        assertThat(amend.get(1, TimeUnit.SECONDS)).isEqualTo(2L);
        first.get(10, TimeUnit.SECONDS);
    }
}
