package com.example.paymentorders;

import com.example.paymentorders.exception.ShuttingDownException;
import com.mongodb.client.MongoClient;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the {@link MongoClient} and the {@link PaymentOrderService} of a pod, and shuts them down gracefully. It
 * refuses new calls immediately. Calls in progress get a grace period to finish. Then the client closes exactly once.
 */
public final class PaymentOrderRuntime {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderRuntime.class);

    private final MongoClient client;
    private final InFlightGate gate = new InFlightGate();
    private final PaymentOrderService service;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final CountDownLatch shutdownDone = new CountDownLatch(1);

    public PaymentOrderRuntime(MongoClient client, String databaseName) {
        this(client, databaseName, InstantSource.system());
    }

    public PaymentOrderRuntime(MongoClient client, String databaseName, InstantSource clock) {
        this.client = client;
        this.service = new PaymentOrderService(client.getDatabase(databaseName), clock, gate);
    }

    /** One runtime per pod, with the client built by {@link PaymentOrderClients}. */
    public static PaymentOrderRuntime create(String connectionString, String databaseName) {
        return new PaymentOrderRuntime(PaymentOrderClients.create(connectionString), databaseName);
    }

    public PaymentOrderService service() {
        return service;
    }

    /**
     * Stops new calls. A later call throws {@link ShuttingDownException} and does not touch the database. Then it
     * waits for the calls in progress, for a maximum of {@code grace}. Then it closes the client, also if the grace
     * period ran out. The close does not stop a call that already uses a connection (driver 5.13). That call finishes
     * independently, within the client's {@code timeoutMS}, and reports its result normally. This method is
     * idempotent and thread-safe: only the first call does something. A later call waits until the first one is
     * complete. Thus, a JVM shutdown hook that calls second cannot let the JVM stop during the drain.
     */
    public void shutdown(Duration grace) {
        if (!shutdownStarted.compareAndSet(false, true)) {
            awaitShutdownDone();
            return;
        }
        gate.close();
        try {
            if (!gate.awaitDrained(grace)) {
                log.warn("Grace period of {} ran out with calls still in progress; closing the client", grace);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                client.close();
            } finally {
                shutdownDone.countDown();
            }
        }
    }

    private void awaitShutdownDone() {
        try {
            shutdownDone.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Calls {@link #shutdown(Duration)} when the JVM stops (for example, on SIGTERM from Kubernetes). The termination
     * grace period of the pod must be longer than the {@code preStop} sleep plus {@code grace} plus the client's
     * {@code timeoutMS}. Then a call that still runs after the grace period can finish before the process stops.
     */
    public void registerShutdownHook(Duration grace) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(grace), "payment-orders-shutdown"));
    }
}
