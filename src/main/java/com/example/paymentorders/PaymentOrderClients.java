package com.example.paymentorders;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.example.optimistic.OptimisticLockingWriter;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.management.JMXConnectionPoolListener;
import org.bson.codecs.configuration.CodecRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates one MongoClient for each pod. The defaults are for a long-running OLTP service with bursts and no known
 * target load (refer to DESIGN.md section 2). An option in the connection string overrides a default. The
 * exception is retryWrites.
 */
public final class PaymentOrderClients {

    static final long DEFAULT_TIMEOUT_SECONDS = 10;

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderClients.class);

    /** The driver's codecs, but a {@code Document} decodes each BSON date as an {@code Instant}, for every reader. */
    private static final CodecRegistry CODECS =
            OptimisticLockingWriter.withInstantDates(MongoClientSettings.getDefaultCodecRegistry());

    private PaymentOrderClients() {
    }

    /** The pod name (Kubernetes sets HOSTNAME to it), else "payment-orders". */
    static String defaultApplicationName() {
        String hostname = System.getenv("HOSTNAME");
        return hostname == null || hostname.isBlank() ? "payment-orders" : hostname;
    }

    public static MongoClient create(String connectionString) {
        return MongoClients.create(settings(connectionString));
    }

    static MongoClientSettings settings(String connectionString) {
        ConnectionString uri = new ConnectionString(connectionString);
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .codecRegistry(CODECS)
                .applyToConnectionPoolSettings(pool -> pool
                        .maxSize(100)                        // driver default; must exceed request threads per pod
                        .minSize(0)                          // applies per replica-set member; secondaries get no traffic
                        .maxConnecting(2)                    // driver default; no thundering herd on scale-up
                        .maxWaitTime(5, SECONDS)             // pool wait only without timeoutMS (driver default 120 s);
                                                             // with timeoutMS, the driver uses timeoutMS instead
                        .maxConnectionIdleTime(5, MINUTES))  // reuse during bursts, prune afterwards
                .applyToSocketSettings(socket -> socket.connectTimeout(10, SECONDS))
                .applyToClusterSettings(cluster -> cluster
                        .serverSelectionTimeout(DEFAULT_TIMEOUT_SECONDS, SECONDS)); // consistent with timeoutMS
        // A deadline per operation, so a stalled primary or lagging majority can't hold threads forever. Not applied
        // when the URI sets timeoutMS or socketTimeoutMS: with timeoutMS the driver ignores socketTimeoutMS.
        // waitQueueTimeoutMS alone does not turn it off. Under timeoutMS the driver ignores it, so a WARN says so.
        if (uri.getTimeout() == null && uri.getSocketTimeout() == null) {
            builder.timeout(DEFAULT_TIMEOUT_SECONDS, SECONDS);
            if (uri.getMaxWaitTime() != null) {
                // With timeoutMS, the driver limits the wait for a connection by timeoutMS, not waitQueueTimeoutMS.
                log.warn("waitQueueTimeoutMS={} is ignored under the default timeoutMS={}s; set timeoutMS to change it",
                        uri.getMaxWaitTime(), DEFAULT_TIMEOUT_SECONDS);
            }
        } else if (uri.getTimeout() == null) {
            log.warn("socketTimeoutMS without timeoutMS: legacy timeouts, the driver retries a write only once");
        }
        if (uri.getApplicationName() == null) {
            // Atlas, server logs and currentOp then show which pod opened a connection.
            builder.applicationName(defaultApplicationName());
        }
        return builder
                .applyToConnectionPoolSettings(pool -> pool.addConnectionPoolListener(new JMXConnectionPoolListener()))
                .applyConnectionString(uri)
                .retryWrites(true) // always: RetriedWriteException's reasoning depends on it
                .build();
    }
}
