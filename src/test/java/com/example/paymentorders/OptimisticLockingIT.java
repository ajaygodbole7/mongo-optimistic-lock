package com.example.paymentorders;

import static com.example.paymentorders.MongoTestSupport.collection;
import static com.example.paymentorders.MongoTestSupport.sampleOrder;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.optimistic.OptimisticLockingWriter;
import com.example.optimistic.RetryPolicy;
import com.example.optimistic.exception.RetriesExhaustedException;
import com.example.optimistic.exception.SchemaValidationException;
import com.example.optimistic.exception.NotSentException;
import com.example.optimistic.exception.VersionConflictException;
import com.example.paymentorders.exception.InvalidStatusException;
import com.example.paymentorders.exception.OrderNotFoundException;
import com.example.paymentorders.exception.OrderValidationException;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import org.bson.BsonDocument;
import org.bson.BsonType;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OptimisticLockingIT {

    /** Fixed clocks with sub-millisecond parts. The stored timestamps must be these, truncated to milliseconds. */
    private static final Instant CREATED_AT = Instant.parse("2026-11-02T08:00:00.123456789Z");
    private static final Instant AMENDED_AT = Instant.parse("2026-11-02T09:30:00.987654321Z");
    private static final Instant CREATED_MS = Instant.parse("2026-11-02T08:00:00.123Z");
    private static final Instant AMENDED_MS = Instant.parse("2026-11-02T09:30:00.987Z");

    private static MongoClient client;
    private PaymentOrderService service;
    private PaymentOrderService laterService;

    @BeforeAll
    static void connect() {
        client = MongoTestSupport.newPod();
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @BeforeEach
    void reset() {
        MongoTestSupport.resetCollection(client);
        service = new PaymentOrderService(MongoTestSupport.database(client), InstantSource.fixed(CREATED_AT));
        laterService = new PaymentOrderService(MongoTestSupport.database(client), InstantSource.fixed(AMENDED_AT));
    }

    private Document load(ObjectId id) {
        return collection(client).find(eq("_id", id)).first();
    }

    private static Map<String, Object> remittance(String unstructured) {
        return Map.of("remittance", new NewPaymentOrder.Remittance("INV-4471", unstructured));
    }

    @Test
    void t1_createSetsVersion1AndWhoColumns() {
        ObjectId id = service.create(sampleOrder(), "alice");

        Document doc = load(id);
        assertThat(doc.get("_documentMeta", Document.class).get("version")).isEqualTo(1L);
        assertThat(doc.get("_documentMeta", Document.class).keySet()).containsExactly("version");
        assertThat(doc.getString("status")).isEqualTo("DRAFT");
        assertThat(doc.getString("_createdBy")).isEqualTo("alice");
        assertThat(doc.getString("_lastModifiedBy")).isEqualTo("alice");
        assertThat(doc.get("_createdDate", Instant.class)).isEqualTo(CREATED_MS);
        assertThat(doc.get("_lastModifiedDate", Instant.class)).isEqualTo(CREATED_MS);
        assertThat(doc.get("amount")).isEqualTo(new Decimal128(new BigDecimal("1250.00")));
        assertThat(doc.getEmbedded(List.of("debtor", "account"), String.class)).isEqualTo("ACC-0001");
        assertThat(doc.getEmbedded(List.of("creditor", "address", "city"), String.class)).isEqualTo("Berlin");
        assertThat(doc.getEmbedded(List.of("remittance", "reference"), String.class)).isEqualTo("INV-4471");
    }

    @Test
    void t17_whoDatesAreStoredAsBsonDates() {
        ObjectId id = service.create(sampleOrder(), "alice");
        BsonDocument created = collection(client).withDocumentClass(BsonDocument.class).find(eq("_id", id)).first();

        assertThat(created.get("_createdDate").getBsonType()).isEqualTo(BsonType.DATE_TIME);
        assertThat(created.get("_lastModifiedDate").getBsonType()).isEqualTo(BsonType.DATE_TIME);
        assertThat(created.getDateTime("_createdDate").getValue()).isEqualTo(CREATED_MS.toEpochMilli());
        assertThat(created.getDateTime("_lastModifiedDate").getValue()).isEqualTo(CREATED_MS.toEpochMilli());

        laterService.amend(id, 1L, remittance("edit"), "bob");
        BsonDocument amended = collection(client).withDocumentClass(BsonDocument.class).find(eq("_id", id)).first();
        assertThat(amended.get("_lastModifiedDate").getBsonType()).isEqualTo(BsonType.DATE_TIME);
        assertThat(amended.getDateTime("_createdDate").getValue()).isEqualTo(CREATED_MS.toEpochMilli());
        assertThat(amended.getDateTime("_lastModifiedDate").getValue()).isEqualTo(AMENDED_MS.toEpochMilli());

        // The driver, not the code, dropped the sub-millisecond part. The client reads the dates back as Instant.
        assertThat(load(id).get("_createdDate")).isEqualTo(CREATED_MS);
        assertThat(load(id).get("_lastModifiedDate")).isEqualTo(AMENDED_MS);
    }

    @Test
    void t2_amendWithCurrentVersion() {
        ObjectId id = service.create(sampleOrder(), "alice");
        Document before = load(id);

        long version = laterService.amend(id, 1L,
                Map.of("amount", new BigDecimal("99.95"),
                        "remittance", new NewPaymentOrder.Remittance("INV-4472", "Invoice 4472")),
                "bob");

        Document after = load(id);
        assertThat(version).isEqualTo(2L);
        assertThat(OptimisticLockingWriter.readVersion(after)).isEqualTo(2L);
        assertThat(after.get("amount")).isEqualTo(new Decimal128(new BigDecimal("99.95")));
        assertThat(after.getEmbedded(List.of("remittance", "unstructured"), String.class)).isEqualTo("Invoice 4472");
        assertThat(after.get("_createdDate", Instant.class)).isEqualTo(CREATED_MS);
        assertThat(after.getString("_createdBy")).isEqualTo("alice");
        assertThat(after.getString("_lastModifiedBy")).isEqualTo("bob");
        assertThat(after.get("_lastModifiedDate", Instant.class)).isEqualTo(AMENDED_MS);
    }

    @Test
    void t3_amendWithStaleVersionConflicts() {
        ObjectId id = service.create(sampleOrder(), "alice");
        service.amend(id, 1L, remittance("first"), "bob");
        Document before = load(id);

        assertThatThrownBy(() -> service.amend(id, 1L, remittance("stale"), "carol"))
                .isInstanceOfSatisfying(VersionConflictException.class, e -> {
                    assertThat(e.id()).isEqualTo(id);
                    assertThat(e.expectedVersion()).isEqualTo(1L);
                    assertThat(e.actualVersion()).isEqualTo(2L);
                });
        assertThat(load(id)).isEqualTo(before);
    }

    @Test
    void t4_amendSubmittedIsInvalidStatus() {
        ObjectId id = service.create(sampleOrder(), "alice");
        long version = service.submit(id, 1L, "alice");

        assertThatThrownBy(() -> service.amend(id, version, remittance("late"), "bob"))
                .isInstanceOfSatisfying(InvalidStatusException.class,
                        e -> assertThat(e.actualStatus()).isEqualTo("SUBMITTED"));
        assertThat(OptimisticLockingWriter.readVersion(load(id))).isEqualTo(2L);
    }

    @Test
    void t5_amendMissingIdNotFound() {
        ObjectId missing = new ObjectId();

        assertThatThrownBy(() -> service.amend(missing, 1L, remittance("x"), "bob"))
                .isInstanceOfSatisfying(OrderNotFoundException.class, e -> assertThat(e.id()).isEqualTo(missing));
    }

    @Test
    void t6_submitTwice() {
        ObjectId id = service.create(sampleOrder(), "alice");

        long version = service.submit(id, 1L, "alice");
        assertThat(version).isEqualTo(2L);
        assertThat(load(id).getString("status")).isEqualTo("SUBMITTED");

        assertThatThrownBy(() -> service.submit(id, version, "alice"))
                .isInstanceOf(InvalidStatusException.class);
        assertThat(OptimisticLockingWriter.readVersion(load(id))).isEqualTo(2L);
    }

    @Test
    void t7_cancelFromEachStatus() {
        ObjectId draft = service.create(sampleOrder(), "alice");
        assertThat(service.cancel(draft, 1L, "alice")).isEqualTo(2L);
        assertThat(load(draft).getString("status")).isEqualTo("CANCELLED");

        ObjectId submitted = service.create(sampleOrder(), "alice");
        long v = service.submit(submitted, 1L, "alice");
        assertThat(service.cancel(submitted, v, "alice")).isEqualTo(3L);
        assertThat(load(submitted).getString("status")).isEqualTo("CANCELLED");

        assertThatThrownBy(() -> service.cancel(draft, 2L, "alice"))
                .isInstanceOfSatisfying(InvalidStatusException.class,
                        e -> assertThat(e.actualStatus()).isEqualTo("CANCELLED"));
    }

    @Test
    void amendRejectsNonAmendableFields() {
        ObjectId id = service.create(sampleOrder(), "alice");

        List<Map<String, ?>> invalid = List.of(
                Map.of(),
                Map.of("_createdBy", "x"), Map.of("_createdDate", "x"),
                Map.of("_documentMeta", "x"), Map.of("status", "SUBMITTED"), Map.of("_id", "x"),
                Map.of("creditor.address", "x"));
        for (Map<String, ?> changes : invalid) {
            assertThatThrownBy(() -> service.amend(id, 1L, changes, "bob"))
                    .as("changes %s", changes)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(OptimisticLockingWriter.readVersion(load(id))).isEqualTo(1L);
    }

    @Test
    void t12_updateCreditorAddressChangesOnlyTheAddress() {
        ObjectId id = service.create(sampleOrder(), "alice");
        Document before = load(id);
        var munich = new NewPaymentOrder.Address("2 Isar Weg", "Munich", "80331", "DE");

        long version = service.updateCreditorAddress(id, 1L, munich, "bob");

        Document after = load(id);
        assertThat(version).isEqualTo(2L);
        assertThat(after.getEmbedded(List.of("creditor", "address"), Document.class)).isEqualTo(new Document()
                .append("street", "2 Isar Weg").append("city", "Munich")
                .append("postCode", "80331").append("country", "DE"));
        assertThat(after.getEmbedded(List.of("creditor", "name"), String.class)).isEqualTo("Acme Ltd");
        assertThat(after.getEmbedded(List.of("creditor", "iban"), String.class))
                .isEqualTo("DE89370400440532013000");

        // Every other field is untouched; only the address, last-modified pair and version moved.
        Set<String> changed = Set.of("creditor", "_lastModifiedDate", "_lastModifiedBy", "_documentMeta");
        assertThat(after.keySet()).isEqualTo(before.keySet());
        for (String key : before.keySet()) {
            if (!changed.contains(key)) {
                assertThat(after.get(key)).as(key).isEqualTo(before.get(key));
            }
        }
        assertThat(after.getString("_lastModifiedBy")).isEqualTo("bob");
    }

    /** The service rejects input that is not valid, with field errors, before it touches the database. */
    @Test
    void t18_invalidInputRejectedByServiceLeavesOrderUnchanged() {
        ObjectId id = service.create(sampleOrder(), "alice");
        Document before = load(id);

        var creditorWithoutAddress = new NewPaymentOrder.Creditor("Acme Ltd", "DE89370400440532013000", null);
        assertThatThrownBy(() -> service.amend(id, 1L, Map.of("creditor", creditorWithoutAddress), "bob"))
                .isInstanceOfSatisfying(OrderValidationException.class, e -> {
                    assertThat(e.errors()).extracting(Object::toString).containsExactly("creditor.address: required");
                    assertThat(e.getCause()).as("never reached the database").isNull();
                });

        var addressWithoutCountry = new NewPaymentOrder.Address("2 Isar Weg", "Munich", "80331", null);
        assertThatThrownBy(() -> service.updateCreditorAddress(id, 1L, addressWithoutCountry, "bob"))
                .isInstanceOfSatisfying(OrderValidationException.class, e -> assertThat(e.errors())
                        .extracting(Object::toString).containsExactly("creditor.address.country: required"));

        assertThat(load(id)).isEqualTo(before);
    }

    @Test
    void t19_insertRejectsSystemFields() {
        PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(client));

        for (String field : OptimisticLockingWriter.SYSTEM_FIELDS) {
            Document fields = new Document("status", "DRAFT").append(field, "x");
            assertThatThrownBy(() -> writer.insert(fields, "alice"))
                    .as(field)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(collection(client).countDocuments()).isZero();
    }

    @Test
    void t15_systemRetryDoesNotRetryNotFoundOrInvalidStatus() {
        PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(client));
        AtomicInteger recomputes = new AtomicInteger();
        Function<Document, Bson> recompute = doc -> {
            recomputes.incrementAndGet();
            return MongoTestSupport.incrementAmount(doc);
        };

        ObjectId cancelled = service.create(sampleOrder(), "alice");
        service.cancel(cancelled, 1L, "alice");
        assertThatThrownBy(() -> writer.updateWithRetry(
                cancelled, Set.of(PaymentOrderStatus.DRAFT), recompute, "system", RetryPolicy.defaults()))
                .isInstanceOf(InvalidStatusException.class);
        assertThat(recomputes).as("rejected before recompute, no retry").hasValue(0);
        assertThat(OptimisticLockingWriter.readVersion(load(cancelled))).isEqualTo(2L);

        recomputes.set(0);
        assertThatThrownBy(() -> writer.updateWithRetry(
                new ObjectId(), Set.of(PaymentOrderStatus.DRAFT), recompute, "system", RetryPolicy.defaults()))
                .isInstanceOf(OrderNotFoundException.class);
        assertThat(recomputes).hasValue(0);
    }

    @Test
    void t15_systemRetryRecomputesFromFreshDocument() {
        PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(client));
        ObjectId id = service.create(sampleOrder(), "alice");
        service.amend(id, 1L, remittance("user edit"), "bob");

        long version = writer.updateWithRetry(id, Set.of(PaymentOrderStatus.DRAFT),
                MongoTestSupport::incrementAmount, "system", RetryPolicy.defaults());

        Document doc = load(id);
        assertThat(version).isEqualTo(3L);
        assertThat(MongoTestSupport.amountOf(doc)).isEqualByComparingTo("1251.00");
        assertThat(doc.getString("_lastModifiedBy")).isEqualTo("system");
    }

    @Test
    void t25_writerRejectsChangesToSystemFields() {
        PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(client));
        ObjectId id = service.create(sampleOrder(), "alice");
        Set<PaymentOrderStatus> draft = Set.of(PaymentOrderStatus.DRAFT);

        for (Bson change : List.of(Updates.set("_createdBy", "mallory"), Updates.set("_documentMeta.version", 9L),
                Updates.unset("_createdDate"), Updates.set("_id", new ObjectId()),
                Updates.rename("debtor.name", "_createdBy"),       // the target is a value, not a key
                Updates.rename("debtor.name", "debtor.alias"))) {  // any operator outside the allow-list
            assertThatThrownBy(() -> writer.update(id, 1L, draft, change, "bob")).as("%s", change)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(OptimisticLockingWriter.readVersion(load(id))).isEqualTo(1L);
        assertThat(load(id).getString("_createdBy")).isEqualTo("alice");
    }

    @Test
    void t26_getReturnsTheStoredOrderOrNothing() {
        ObjectId id = service.create(sampleOrder(), "alice");
        laterService.amend(id, 1L, remittance("edit"), "bob");

        Document order = service.get(id).orElseThrow();
        assertThat(OptimisticLockingWriter.readVersion(order)).isEqualTo(2L);
        assertThat(order.getEmbedded(List.of("remittance", "unstructured"), String.class)).isEqualTo("edit");
        assertThat(service.get(new ObjectId())).isEmpty();
    }

    /**
     * An interrupt during the wait between attempts is a cancellation, not a business conflict. The interrupt must
     * occur during the wait. If the thread is already interrupted, the driver fails its next call with
     * MongoInterruptedException, which is correctly "maybe written". The worker is a platform thread on purpose: a
     * platform thread that waits for a socket is RUNNABLE, so TIMED_WAITING means the backoff sleep. A virtual thread
     * reports TIMED_WAITING for both.
     */
    @Test
    void t27_interruptDuringRetryBackoffIsTransientNotApplied() throws Exception {
        PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(client));
        ObjectId id = service.create(sampleOrder(), "alice");
        RetryPolicy longWaits = new RetryPolicy(50, Duration.ofSeconds(5), Duration.ofSeconds(5));
        AtomicInteger recomputes = new AtomicInteger();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        // Every recompute loses: another writer moves the version first, so the attempt conflicts and waits.
        Thread worker = new Thread(() -> {
            try {
                writer.updateWithRetry(id, Set.of(PaymentOrderStatus.DRAFT), doc -> {
                    laterService.amend(id, OptimisticLockingWriter.readVersion(doc),
                            remittance("other writer " + recomputes.incrementAndGet()), "bob");
                    return MongoTestSupport.incrementAmount(doc);
                }, "system", longWaits);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        worker.start();
        for (int i = 0; i < 5000 && worker.isAlive(); i++) {
            if (recomputes.get() > 0 && worker.getState() == Thread.State.TIMED_WAITING) { // in the backoff sleep
                worker.interrupt();
                break;
            }
            LockSupport.parkNanos(1_000_000);
        }
        worker.join(10_000);

        assertThat(thrown.get()).as("%s", thrown.get()).isInstanceOfSatisfying(NotSentException.class, e -> {
            assertThat(e.getCause()).isInstanceOf(InterruptedException.class);
        });
        assertThat(MongoTestSupport.amountOf(load(id))).as("the system write never applied")
                .isEqualByComparingTo("1250.00");
    }

    @Test
    void t28_recomputeWithNoChangeDoesNotWrite() {
        PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(client));
        ObjectId id = service.create(sampleOrder(), "alice");
        Document before = load(id);

        long version = writer.updateWithRetry(id, Set.of(PaymentOrderStatus.DRAFT), doc -> null, "system",
                RetryPolicy.defaults());

        assertThat(version).isEqualTo(1L);
        assertThat(load(id)).isEqualTo(before);
    }

    @Test
    void t30_serviceUpdateWithRetryAppliesTheSystemChange() {
        ObjectId id = service.create(sampleOrder(), "alice");

        long version = service.updateWithRetry(id, Set.of(PaymentOrderStatus.DRAFT), MongoTestSupport::incrementAmount,
                "system", RetryPolicy.defaults());

        assertThat(version).isEqualTo(2L);
        assertThat(MongoTestSupport.amountOf(load(id))).isEqualByComparingTo("1251.00");
    }

    /**
     * T31: {@code apply} replaces the validator of an existing collection. An old document that does not match the new
     * rules can still be updated under {@code moderate}, but not under {@code strict}.
     */
    @Test
    void t31_applyUpdatesTheValidatorOfAnExistingCollection() {
        // An "old" deployment: the collection exists with a looser validator (here: none), and holds an old order
        // with a lower-case currency, which the current rules reject.
        collection(client).drop();
        MongoTestSupport.database(client).createCollection(PaymentOrderSchema.COLLECTION);
        Document legacy = validRawOrder().append("currency", "eur");
        collection(client).insertOne(legacy);
        ObjectId id = legacy.getObjectId("_id");

        PaymentOrderSchema.apply(MongoTestSupport.database(client), ValidationLevel.MODERATE, ValidationAction.ERROR);
        assertThat(service.amend(id, 1L, remittance("moderate"), "bob")).as("moderate: old document").isEqualTo(2L);

        PaymentOrderSchema.apply(MongoTestSupport.database(client));
        assertThatThrownBy(() -> service.amend(id, 2L, remittance("strict"), "bob")).as("strict: old document")
                .isInstanceOfSatisfying(OrderValidationException.class, OptimisticLockingIT::assertRejectedByServer);
        assertRejectedByValidator(validRawOrder().append("currency", "usd"));
        assertThat(service.amend(id, 2L, Map.of("currency", "EUR"), "bob")).as("a fix of the old document").isEqualTo(3L);
    }

    /** Failed attempts do not do the diagnosis read. Only the last attempt does it. */
    @Test
    void t29_retryLoopReadsOncePerAttemptAndDiagnosesOnlyAtTheEnd() {
        AtomicInteger finds = new AtomicInteger();
        CommandListener countFinds = new CommandListener() {
            @Override
            public void commandStarted(CommandStartedEvent event) {
                if (event.getCommandName().equals("find")) {
                    finds.incrementAndGet();
                }
            }
        };
        try (MongoClient counted = MongoClients.create(MongoClientSettings.builder(
                PaymentOrderClients.settings(MongoTestSupport.connectionString(""))).addCommandListener(countFinds)
                .build())) {
            PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(counted));
            ObjectId id = service.create(sampleOrder(), "alice");
            RetryPolicy threeAttempts = new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(1));

            // Every attempt loses: another writer moves the version between the read and the update.
            assertThatThrownBy(() -> writer.updateWithRetry(id, Set.of(PaymentOrderStatus.DRAFT), doc -> {
                laterService.amend(id, OptimisticLockingWriter.readVersion(doc), remittance("other"), "bob");
                return MongoTestSupport.incrementAmount(doc);
            }, "system", threeAttempts)).isInstanceOfSatisfying(RetriesExhaustedException.class, e -> {
                assertThat(e.getCause()).isInstanceOf(VersionConflictException.class);
                assertThat(e).as("a user-conflict catch must not catch it").isNotInstanceOf(
                        VersionConflictException.class);
            });

            assertThat(finds).as("3 attempt reads + 1 diagnosis read").hasValue(4);
        }
    }

    /** The validator does not allow this state. Thus, the test creates the collection again without the validator. */
    @Test
    void t20_missingVersionThrowsInsteadOfDefaulting() {
        collection(client).drop();
        MongoTestSupport.database(client).createCollection(PaymentOrderSchema.COLLECTION);
        Document noVersion = validRawOrder();
        noVersion.remove("_documentMeta");
        collection(client).insertOne(noVersion);
        ObjectId id = noVersion.getObjectId("_id");
        Document before = load(id);

        assertThatThrownBy(() -> service.amend(id, 1L, remittance("x"), "bob"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("_documentMeta.version");
        assertThatThrownBy(() -> OptimisticLockingWriter.readVersion(new Document("_documentMeta", new Document())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(load(id)).isEqualTo(before);
    }

    /** A raw document that passes the validator. */
    private static Document validRawOrder() {
        Instant now = CREATED_MS;
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

    /** The MongoDB validator, not the service, rejected the write. */
    static void assertRejectedByServer(OrderValidationException e) {
        assertThat(e.errors()).isEmpty();
        assertThat(e.reason()).isEqualTo(SchemaValidationException.Reason.REJECTED_BY_VALIDATOR);
        assertThat(e).hasMessageContaining("was rejected by the validator");
        assertThat(e.getCause()).isInstanceOfSatisfying(MongoWriteException.class,
                cause -> assertThat(cause.getCode()).isEqualTo(121));
    }

    private void assertRejectedByValidator(Document doc) {
        assertThatThrownBy(() -> collection(client).insertOne(doc))
                .isInstanceOfSatisfying(MongoWriteException.class, e -> assertThat(e.getCode()).isEqualTo(121));
    }

    @Test
    void t11_validatorAcceptsValidRawOrder() {
        collection(client).insertOne(validRawOrder());
        assertThat(collection(client).countDocuments()).isEqualTo(1);
    }

    @Test
    void t11_validatorRejectsMissingCreatedBy() {
        Document doc = validRawOrder();
        doc.remove("_createdBy");
        assertRejectedByValidator(doc);
    }

    @Test
    void t11d_validatorRejectsEmptyCreatedBy() {
        assertRejectedByValidator(validRawOrder().append("_createdBy", ""));
    }

    @Test
    void t11_validatorRejectsExtraMetaField() {
        assertRejectedByValidator(validRawOrder()
                .append("_documentMeta", new Document("version", 1L).append("lockedBy", "pod-1")));
    }

    @Test
    void t11c_validatorRejectsExtraAddressField() {
        Document doc = validRawOrder();
        doc.getEmbedded(List.of("creditor", "address"), Document.class).append("planet", "Earth");
        assertRejectedByValidator(doc);
    }

    @Test
    void t11e_validatorRejectsZeroAmount() {
        assertRejectedByValidator(validRawOrder().append("amount", new Decimal128(BigDecimal.ZERO)));
    }

    @Test
    void t11c_validatorRejectsUnknownStatus() {
        assertRejectedByValidator(validRawOrder().append("status", "PAID"));
    }
}
