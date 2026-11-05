package com.example.paymentorders;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.MongoClientSettings;
import com.mongodb.management.JMXConnectionPoolListener;
import java.time.Instant;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.Document;
import org.bson.codecs.DecoderContext;
import org.junit.jupiter.api.Test;

class PaymentOrderClientsTest {

    @Test
    void t16_defaultsApplyWhenUriHasNoOptions() {
        MongoClientSettings settings = PaymentOrderClients.settings("mongodb://localhost:27017");

        var pool = settings.getConnectionPoolSettings();
        assertThat(pool.getMaxSize()).isEqualTo(100);
        assertThat(pool.getMinSize()).isZero();
        assertThat(pool.getMaxConnecting()).isEqualTo(2);
        assertThat(pool.getMaxWaitTime(SECONDS)).isEqualTo(5);
        assertThat(pool.getMaxConnectionIdleTime(MINUTES)).isEqualTo(5);
        assertThat(settings.getSocketSettings().getConnectTimeout(SECONDS)).isEqualTo(10);
        assertThat(settings.getClusterSettings().getServerSelectionTimeout(SECONDS)).isEqualTo(10);
        assertThat(settings.getTimeout(SECONDS)).isEqualTo(10);
        assertThat(settings.getRetryWrites()).isTrue();
    }

    @Test
    void t16_uriOptionsOverrideOnlyWhatTheySet() {
        MongoClientSettings settings = PaymentOrderClients.settings(
                "mongodb://localhost:27017/?maxPoolSize=7&minPoolSize=3&timeoutMS=2500");

        var pool = settings.getConnectionPoolSettings();
        assertThat(pool.getMaxSize()).isEqualTo(7);
        assertThat(pool.getMinSize()).isEqualTo(3);
        assertThat(settings.getTimeout(MILLISECONDS)).isEqualTo(2500);
        assertThat(pool.getMaxConnectionIdleTime(MINUTES)).isEqualTo(5);
        assertThat(settings.getClusterSettings().getServerSelectionTimeout(SECONDS)).isEqualTo(10);
    }

    @Test
    void t16_noTimeoutDefaultWhenUriSetsSocketTimeout() {
        assertThat(PaymentOrderClients.settings("mongodb://localhost:27017/?socketTimeoutMS=300").getTimeout(SECONDS))
                .isNull();
    }

    /** waitQueueTimeoutMS limits only the wait for a connection. Without timeoutMS, an operation has no deadline. */
    @Test
    void t16_waitQueueTimeoutAloneKeepsTheDefaultTimeout() {
        assertThat(PaymentOrderClients.settings("mongodb://localhost:27017/?waitQueueTimeoutMS=200")
                .getTimeout(SECONDS)).isEqualTo(10);
    }

    @Test
    void t16_appNameDefaultsToThePodNameUnlessTheUriSetsIt() {
        assertThat(PaymentOrderClients.settings("mongodb://localhost:27017").getApplicationName())
                .isEqualTo(PaymentOrderClients.defaultApplicationName());
        assertThat(PaymentOrderClients.settings("mongodb://localhost:27017/?appName=job-7").getApplicationName())
                .isEqualTo("job-7");
    }

    @Test
    void t16_jmxConnectionPoolListenerIsRegistered() {
        assertThat(PaymentOrderClients.settings("mongodb://localhost:27017").getConnectionPoolSettings()
                .getConnectionPoolListeners()).hasAtLeastOneElementOfType(JMXConnectionPoolListener.class);
    }

    /** A BSON date at the root or in a subdocument decodes as a UTC Instant (not inside an array: G14). */
    @Test
    void t16_bsonDatesDecodeAsInstant() {
        Instant at = Instant.parse("2026-11-02T08:00:00.123Z");
        BsonDocument stored = new BsonDocument("_createdDate", new BsonDateTime(at.toEpochMilli()))
                .append("nested", new BsonDocument("at", new BsonDateTime(at.toEpochMilli())));

        Document decoded = PaymentOrderClients.settings("mongodb://localhost:27017").getCodecRegistry()
                .get(Document.class).decode(new BsonDocumentReader(stored), DecoderContext.builder().build());

        assertThat(decoded.get("_createdDate")).isEqualTo(at);
        assertThat(decoded.get("nested", Document.class).get("at")).isEqualTo(at);
    }

    @Test
    void t16_retryWritesIsAlwaysOn() {
        assertThat(PaymentOrderClients.settings("mongodb://localhost:27017/?retryWrites=false").getRetryWrites())
                .isTrue();
    }
}
