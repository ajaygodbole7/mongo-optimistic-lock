package com.example.paymentorders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Updates;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The MongoDB that the tests use. All test classes in the JVM share it. The default is a local Testcontainers
 * single-node replica set. With {@code -Dmongo.external=true} (the {@code atlas} profile sets it) and the
 * {@code MONGO_URI} environment variable, it is an external cluster, for example Atlas. The tests then use a
 * database with a unique name, and drop their collection when the JVM stops. It is public for the tests of the
 * generic writer ({@code com.example.optimistic}).
 */
public final class MongoTestSupport {

    static final boolean EXTERNAL = Boolean.getBoolean("mongo.external");

    private static final String EXTERNAL_URI = System.getenv("MONGO_URI");

    static final String DB = EXTERNAL ? "olock_test_" + new ObjectId() : "payments";

    /**
     * Runs each task on a new virtual thread. For {@code CompletableFuture}: tasks that block stay off the common
     * pool.
     */
    public static final Executor VIRTUAL_THREADS = task -> Thread.ofVirtual().start(task);

    // Server error codes that the tests make with the failCommand fail point, or expect from the server.
    public static final int BAD_VALUE = 2;
    public static final int FAILED_TO_PARSE = 9;
    static final int UNAUTHORIZED = 13;
    public static final int SHUTDOWN_IN_PROGRESS = 91;
    static final int DOCUMENT_VALIDATION_FAILURE = 121;
    static final int NOT_WRITABLE_PRIMARY = 10107; // what a primary that stepped down returns

    static {
        if (EXTERNAL) {
            if (EXTERNAL_URI == null || EXTERNAL_URI.isBlank()) {
                throw new IllegalStateException("mongo.external=true needs the MONGO_URI environment variable");
            }
            // Dropping the only collection removes the database; dropDatabase would need more than readWrite.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (MongoClient client = PaymentOrderClients.create(EXTERNAL_URI)) {
                    collection(client).drop();
                }
            }));
        }
    }

    /** Starts only on first use. Thus, external runs do not need Docker. */
    private static final class Local {
        // Testcontainers 2.x starts a standalone mongod unless withReplicaSet() is called; that enables replica-set
        // initiation and sets the command to "--replSet docker-rs". The override below keeps that flag and adds
        // enableTestCommands for the failCommand fail point (ErrorMappingIT, ShutdownIT). Test container only.
        static final MongoDBContainer MONGO = new MongoDBContainer("mongo:9.0.4")
                .withReplicaSet()
                .withCommand("--replSet", "docker-rs", "--setParameter", "enableTestCommands=1");

        static {
            MONGO.start();
        }
    }

    private MongoTestSupport() {
    }

    private static String baseConnectionString() {
        return EXTERNAL ? EXTERNAL_URI : Local.MONGO.getConnectionString();
    }

    /** Where the tests run, for logs. It does not include credentials. */
    static String describeTarget() {
        return EXTERNAL ? "external cluster (MONGO_URI), database " + DB : "local container, database " + DB;
    }

    /** A new client with its own pool and no shared state. It is one simulated pod. */
    public static MongoClient newPod() {
        return PaymentOrderClients.create(baseConnectionString());
    }

    /** Like {@link #newPod()}, with extra connection-string options such as {@code maxPoolSize=50}. */
    static MongoClient newPod(String options) {
        return PaymentOrderClients.create(connectionString(options));
    }

    /** The connection string of the container, with the extra options added. */
    public static String connectionString(String options) {
        String base = baseConnectionString();
        if (options.isBlank()) {
            return base;
        }
        String hostsAndPath = base.substring(base.indexOf("://") + 3);
        String separator = base.contains("?") ? "&" : hostsAndPath.contains("/") ? "?" : "/?";
        return base + separator + options;
    }

    public static MongoDatabase database(MongoClient client) {
        return client.getDatabase(DB);
    }

    static MongoCollection<Document> collection(MongoClient client) {
        return database(client).getCollection(PaymentOrderSchema.COLLECTION);
    }

    static void resetCollection(MongoClient client) {
        collection(client).drop();
        PaymentOrderSchema.apply(database(client));
    }

    /** The recompute function for system writes in tests: amount + 1, calculated from the current document. */
    static Bson incrementAmount(Document order) {
        return Updates.set("amount", new Decimal128(amountOf(order).add(BigDecimal.ONE)));
    }

    /** The version of a payment order, at the default path. The tests know where it is. */
    static long version(Document order) {
        return order.getEmbedded(List.of("_documentMeta", "version"), Number.class).longValue();
    }

    static BigDecimal amountOf(Document order) {
        return order.get("amount", Decimal128.class).bigDecimalValue();
    }

    static NewPaymentOrder sampleOrder() {
        return new NewPaymentOrder(
                new NewPaymentOrder.Debtor("ACC-0001", "Globex GmbH"),
                new NewPaymentOrder.Creditor("Acme Ltd", "DE89370400440532013000",
                        new NewPaymentOrder.Address("1 Main St", "Berlin", "10115", "DE")),
                new BigDecimal("1250.00"),
                "EUR",
                new NewPaymentOrder.Remittance("INV-4471", "Invoice 4471"));
    }

    /** A raw document that passes the validator. */
    static Document validRawOrder() {
        Instant now = Instant.parse("2026-11-02T08:00:00.123Z");
        return new Document("status", "DRAFT")
                .append("debtor", new Document("account", "ACC-0001"))
                .append("creditor", new Document("name", "Acme Ltd").append("iban", "DE89370400440532013000")
                        .append("address", new Document("country", "DE")))
                .append("amount", new Decimal128(new BigDecimal("1.00")))
                .append("currency", "EUR")
                .append("_documentMeta", new Document("version", 1L))
                .append("_createdDate", now)
                .append("_createdBy", "alice")
                .append("_lastModifiedDate", now)
                .append("_lastModifiedBy", "alice");
    }

    /** A raw insert of {@code doc}, past the service, is rejected by the MongoDB validator. */
    static void assertRejectedByValidator(MongoClient client, Document doc) {
        assertThatThrownBy(() -> collection(client).insertOne(doc)).isInstanceOfSatisfying(MongoWriteException.class,
                e -> assertThat(e.getCode()).isEqualTo(DOCUMENT_VALIDATION_FAILURE));
    }
}
