package com.example.paymentorders;

import static com.example.optimistic.exception.SchemaValidationException.Reason.TOO_LARGE;
import static com.example.paymentorders.MongoTestSupport.collection;
import static com.example.paymentorders.MongoTestSupport.database;
import static com.example.paymentorders.MongoTestSupport.sampleOrder;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.optimistic.OptimisticLockingWriter;
import com.example.optimistic.RetryPolicy;
import com.example.optimistic.exception.DataAccessException;
import com.example.optimistic.exception.NotAuthorisedMongoException;
import com.example.optimistic.exception.NotSentException;
import com.example.optimistic.exception.NotWrittenException;
import com.example.optimistic.exception.ReadTimeoutException;
import com.example.optimistic.exception.ResponseLostException;
import com.example.optimistic.exception.RetriedWriteException;
import com.example.optimistic.exception.ServerOverloadedException;
import com.example.optimistic.exception.TransientDataAccessException;
import com.example.optimistic.exception.WriteNotConfirmedException;
import com.example.optimistic.exception.WriteTimeoutException;
import com.example.paymentorders.exception.OrderValidationException;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.bson.BsonMaximumSizeExceededException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The failCommand fail point causes the driver failures. Each fail point applies only to the {@code appName} of this
 * class. Thus, the setup client and other tests are not affected. Each test turns its fail point off at the end.
 */
class ErrorMappingIT {

    private static final String APP = "error-mapping-it";

    /**
     * A URI with a legacy timeout turns off the default {@code timeoutMS}. Then the driver retries a write exactly
     * one time. The tests that classify the error after that single retry use it. With {@code timeoutMS}, the driver
     * continues to retry until the deadline, and these fail points have no effect on the result (E16).
     */
    private static final String ONE_RETRY = "socketTimeoutMS=0";

    private MongoClient setupClient;
    private final List<MongoClient> clients = new ArrayList<>();
    private ObjectId id;

    @BeforeEach
    void reset() {
        setupClient = MongoTestSupport.newPod();
        MongoTestSupport.resetCollection(setupClient);
        id = new PaymentOrderService(database(setupClient)).create(sampleOrder(), "alice");
    }

    @AfterEach
    void disarmAndClose() {
        try {
            if (setupClient != null) {
                Document off = setupClient.getDatabase("admin")
                        .runCommand(new Document("configureFailPoint", "failCommand").append("mode", "off"));
                assertThat(off.get("ok", Number.class).intValue()).isEqualTo(1);
            }
        } finally {
            clients.forEach(MongoClient::close);
            if (setupClient != null) {
                setupClient.close();
            }
        }
    }

    private PaymentOrderService service(String options) {
        MongoClient client = MongoTestSupport.newPod("appName=" + APP + (options.isEmpty() ? "" : "&" + options));
        clients.add(client);
        return new PaymentOrderService(database(client));
    }

    /** Arms failCommand on {@code update}, only for the clients of this class. */
    private void failUpdates(Object mode, Document data) {
        failCommands(List.of("update"), mode, data);
    }

    /** Arms failCommand on the given commands, only for the clients of this class. */
    private void failCommands(List<String> commands, Object mode, Document data) {
        setupClient.getDatabase("admin").runCommand(new Document("configureFailPoint", "failCommand")
                .append("mode", mode)
                .append("data", data.append("failCommands", commands).append("appName", APP)));
    }

    /** How a caller resolves "maybe written": a majority read, which does not return a write that can roll back. */
    private Document majorityReload() {
        return collection(setupClient).withReadConcern(ReadConcern.MAJORITY).find(eq("_id", id)).first();
    }

    private long version() {
        return OptimisticLockingWriter.readVersion(collection(setupClient).find(eq("_id", id)).first());
    }

    private static Map<String, Object> remittance(String text) {
        return Map.of("remittance", new NewPaymentOrder.Remittance("INV-4471", text));
    }

    /** Runs {@code call}, asserts that it threw one of our exceptions (not a raw driver exception), and returns it. */
    private static DataAccessException ourException(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DataAccessException.class).isNotInstanceOf(MongoException.class);
        return (DataAccessException) thrown;
    }

    private static TransientDataAccessException transientFailure(ThrowingCallable call) {
        DataAccessException e = ourException(call);
        assertThat(e).isInstanceOf(TransientDataAccessException.class);
        assertThat(e.getCause()).isInstanceOf(MongoException.class);
        return (TransientDataAccessException) e;
    }

    @Test
    void e1_validationFailure() {
        var creditorWithoutAddress = new NewPaymentOrder.Creditor("Acme Ltd", "DE89370400440532013000", null);
        Document before = collection(setupClient).find(eq("_id", id)).first();

        DataAccessException e = ourException(
                () -> service("").amend(id, 1L, Map.of("creditor", creditorWithoutAddress), "bob"));

        assertThat(e).isInstanceOf(OrderValidationException.class);
        assertThat(collection(setupClient).find(eq("_id", id)).first()).isEqualTo(before);
    }

    @Test
    void e2_singleElectionErrorIsRetriedByTheDriver() {
        failUpdates(new Document("times", 1), new Document("errorCode", 10107));

        assertThat(service("").amend(id, 1L, remittance("e2"), "bob")).isEqualTo(2L);
        assertThat(version()).isEqualTo(2L);
    }

    @Test
    void e3_rejectedRetryMayHaveBeenWritten() {
        failUpdates(new Document("times", 2), new Document("errorCode", 10107));

        TransientDataAccessException e = transientFailure(() -> service(ONE_RETRY).amend(id, 1L, remittance("e3"), "bob"));

        // The driver reports only the retry's error, so the first attempt might have applied. Here both were
        // rejected, which only a reload can tell the caller.
        assertThat(e).isInstanceOf(RetriedWriteException.class);
        assertThat(version()).isEqualTo(1L);
    }

    @Test
    void e4_droppedConnectionMayHaveBeenWritten() {
        failUpdates(new Document("times", 2), new Document("closeConnection", true));

        TransientDataAccessException e = transientFailure(() -> service(ONE_RETRY).amend(id, 1L, remittance("e4"), "bob"));

        assertThat(e).isInstanceOf(ResponseLostException.class);
    }

    @Test
    void e5_socketReadTimeoutMayHaveBeenWritten() {
        failUpdates(new Document("times", 2), new Document("blockConnection", true).append("blockTimeMS", 1500));

        TransientDataAccessException e = transientFailure(
                () -> service("socketTimeoutMS=300").amend(id, 1L, remittance("e5"), "bob"));

        assertThat(e).isInstanceOf(ResponseLostException.class);
    }

    @Test
    void e6_poolExhaustionIsTransientAndNotApplied() throws Exception {
        ObjectId other = new PaymentOrderService(database(setupClient)).create(sampleOrder(), "alice");
        CountDownLatch firstUpdateSent = new CountDownLatch(1);
        CommandListener listener = new CommandListener() {
            @Override
            public void commandStarted(CommandStartedEvent event) {
                if (event.getCommandName().equals("update")) {
                    firstUpdateSent.countDown();
                }
            }
        };
        MongoClient client = MongoClients.create(MongoClientSettings.builder(PaymentOrderClients.settings(
                        MongoTestSupport.connectionString(
                                "appName=" + APP + "&maxPoolSize=1&waitQueueTimeoutMS=200&" + ONE_RETRY)))
                .addCommandListener(listener).build());
        clients.add(client);
        PaymentOrderService service = new PaymentOrderService(database(client));
        failUpdates(new Document("times", 1), new Document("blockConnection", true).append("blockTimeMS", 1500));

        // The first amend holds the only pooled connection while the server blocks it.
        CompletableFuture<Long> holder = CompletableFuture.supplyAsync(
                () -> service.amend(id, 1L, remittance("holder"), "bob"), MongoTestSupport.VIRTUAL_THREADS);
        assertThat(firstUpdateSent.await(5, TimeUnit.SECONDS)).isTrue();

        TransientDataAccessException e = transientFailure(() -> service.amend(other, 1L, remittance("e6"), "bob"));

        assertThat(e).isInstanceOf(NotSentException.class);
        assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo(2L);
        assertThat(OptimisticLockingWriter.readVersion(collection(setupClient).find(eq("_id", other)).first()))
                .isEqualTo(1L);
    }

    /** E8: a write-concern error means that the primary applied the write. It is "maybe written", not "not written". */
    @Test
    void e8_writeConcernErrorMayHaveBeenWrittenAndWas() {
        failUpdates(new Document("times", 2), new Document("writeConcernError",
                new Document("code", 91).append("codeName", "ShutdownInProgress").append("errmsg", "shutting down")));

        TransientDataAccessException e = transientFailure(() -> service("").amend(id, 1L, remittance("e8"), "bob"));

        assertThat(e).isInstanceOf(WriteNotConfirmedException.class);
        assertThat(version()).as("the primary applied it").isEqualTo(2L);
    }

    /** E9: a client-side operation timeout (timeoutMS) can occur after the write was sent. */
    @Test
    void e9_operationTimeoutAfterSendMayHaveBeenWritten() {
        failUpdates(new Document("times", 2), new Document("blockConnection", true).append("blockTimeMS", 1500));

        TransientDataAccessException e = transientFailure(
                () -> service("timeoutMS=400").amend(id, 1L, remittance("e9"), "bob"));

        assertThat(e).isInstanceOf(WriteTimeoutException.class);
        assertThat(e.getCause()).isInstanceOf(com.mongodb.MongoOperationTimeoutException.class);
    }

    /**
     * E10: a failed read did not write. The read in {@code updateWithRetry} occurs before an update. Thus, a
     * {@code find} fail point affects only that read. (On mongo 9.0, a {@code find} fail point also fires during an
     * {@code update}. Thus, it cannot isolate the diagnosis read that comes after an update.)
     */
    @Test
    void e10_failedReadIsNotWritten() {
        MongoClient client = MongoTestSupport.newPod("appName=" + APP + "&" + ONE_RETRY);
        clients.add(client);
        PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(client));
        failCommands(List.of("find"), new Document("times", 2), new Document("closeConnection", true));

        TransientDataAccessException e = transientFailure(() -> writer.updateWithRetry(id,
                java.util.Set.of(PaymentOrderStatus.DRAFT), MongoTestSupport::incrementAmount, "system",
                RetryPolicy.defaults()));

        assertThat(e).isInstanceOf(NotWrittenException.class);
        assertThat(version()).isEqualTo(1L);
    }

    /** E21: a timeoutMS expiry on a read is its own type. A read does not write, so it is "not written". */
    @Test
    void e21_readTimeoutIsNotWritten() {
        MongoClient client = MongoTestSupport.newPod("appName=" + APP + "&timeoutMS=400");
        clients.add(client);
        PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(client));
        failCommands(List.of("find"), new Document("times", 2),
                new Document("blockConnection", true).append("blockTimeMS", 1500));

        TransientDataAccessException e = transientFailure(() -> writer.updateWithRetry(id,
                java.util.Set.of(PaymentOrderStatus.DRAFT), MongoTestSupport::incrementAmount, "system",
                RetryPolicy.defaults()));

        assertThat(e).isInstanceOf(ReadTimeoutException.class);
        assertThat(e.getCause()).isInstanceOf(com.mongodb.MongoOperationTimeoutException.class);
        assertThat(version()).isEqualTo(1L);
    }

    /** E11: after a failed retry, "not primary" can hide a first attempt that applied. For an insert, say unknown. */
    @Test
    void e11_insertRejectedByNonPrimaryMayHaveBeenWritten() {
        failCommands(List.of("insert"), new Document("times", 2), new Document("errorCode", 10107));

        TransientDataAccessException e = transientFailure(() -> service(ONE_RETRY).create(sampleOrder(), "bob"));

        assertThat(e).isInstanceOf(RetriedWriteException.class);
        // The writer generates the _id before sending, so the caller can look the order up instead of creating
        // it again (which could duplicate it). Here both attempts were rejected, so it does not exist.
        assertThat(e.id()).as("generated id carried for lookup").isNotNull();
        assertThat(collection(setupClient).find(eq("_id", e.id())).first()).isNull();
    }

    /** E7: after a "maybe written" failure, the caller reloads to find the result. */
    @Test
    void e7_reloadTellsWhetherItWasWritten() {
        failUpdates(new Document("times", 2), new Document("closeConnection", true));
        TransientDataAccessException e = transientFailure(() -> service(ONE_RETRY).amend(id, 1L, remittance("e7"), "bob"));
        assertThat(e).isInstanceOf(ResponseLostException.class);

        Document reloaded = majorityReload();

        // failCommand closes the connection before the update runs, so the reload shows it was not applied:
        // the version did not move and the remittance is still the original. The caller can retry safely.
        assertThat(OptimisticLockingWriter.readVersion(reloaded)).isEqualTo(1L);
        assertThat(reloaded.getEmbedded(List.of("remittance", "unstructured"), String.class)).isEqualTo("Invoice 4471");
    }

    /** E7b: the reason for "maybe written": the write applied, but the response was lost. */
    @Test
    void e7b_reloadShowsAMaybeWrittenWriteThatApplied() {
        failUpdates(new Document("times", 2), new Document("blockConnection", true).append("blockTimeMS", 1000));

        TransientDataAccessException e = transientFailure(
                () -> service("socketTimeoutMS=300").amend(id, 1L, remittance("e7b"), "bob"));
        assertThat(e).isInstanceOf(ResponseLostException.class);

        // The server runs the update once its block ends; the driver's retry is deduplicated by the server.
        Document reloaded = majorityReload();
        for (int i = 0; i < 50 && OptimisticLockingWriter.readVersion(reloaded) == 1L; i++) {
            reloaded = majorityReload();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }
        assertThat(OptimisticLockingWriter.readVersion(reloaded)).as("applied exactly once").isEqualTo(2L);
        assertThat(reloaded.getEmbedded(List.of("remittance", "unstructured"), String.class)).isEqualTo("e7b");
    }

    /** E12: under timeoutMS, a pool checkout timeout is "maybe written", like each timeoutMS expiry on a write. */
    @Test
    void e12_poolCheckoutTimeoutUnderTimeoutMsIsWriteTimeout() throws Exception {
        ObjectId other = new PaymentOrderService(database(setupClient)).create(sampleOrder(), "alice");
        MongoClient client = MongoTestSupport.newPod("appName=" + APP + "&maxPoolSize=1&timeoutMS=300");
        clients.add(client);
        failUpdates(new Document("times", 1), new Document("blockConnection", true).append("blockTimeMS", 1500));

        // A raw update with its own long deadline holds the only connection while the server blocks it.
        CompletableFuture<?> holder = CompletableFuture.runAsync(() -> collection(client)
                .withTimeout(10, TimeUnit.SECONDS)
                .updateOne(eq("_id", id), new Document("$set", new Document("currency", "EUR"))),
                MongoTestSupport.VIRTUAL_THREADS);
        Document busy = new Document("currentOp", 1).append("appName", APP).append("command.update",
                PaymentOrderSchema.COLLECTION);
        boolean holding = false;
        for (int i = 0; i < 100 && !holding; i++) {
            holding = !setupClient.getDatabase("admin").runCommand(busy).getList("inprog", Document.class).isEmpty();
            if (!holding) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            }
        }
        assertThat(holding).as("the holder's update is in flight and holds the only connection").isTrue();

        TransientDataAccessException e = transientFailure(
                () -> new PaymentOrderService(database(client)).amend(other, 1L, remittance("e12"), "bob"));

        // This attempt was never sent, but the classification cannot tell it from an earlier attempt that was:
        // a timeoutMS expiry on a write is always "maybe written".
        assertThat(e).isInstanceOf(WriteTimeoutException.class);
        holder.get(10, TimeUnit.SECONDS);
        assertThat(OptimisticLockingWriter.readVersion(collection(setupClient).find(eq("_id", other)).first()))
                .isEqualTo(1L);
    }

    /** E13: an operation on a closed client was not sent. */
    @Test
    void e13_callAfterClientClosedIsNotApplied() {
        MongoClient client = MongoTestSupport.newPod("appName=" + APP);
        PaymentOrderService service = new PaymentOrderService(database(client));
        client.close();

        DataAccessException e = ourException(() -> service.amend(id, 1L, remittance("e13"), "bob"));

        assertThat(e).isInstanceOf(NotSentException.class);
        assertThat(e.getCause()).hasMessage("state should be: open");
        assertThat(version()).isEqualTo(1L);
    }

    /** E14: a change that makes the document larger than 16 MB is a validation failure. Nothing is written. */
    @Test
    void e14_oversizedDocumentIsValidationFailure() {
        String huge = "x".repeat(17 * 1024 * 1024);
        // The writer directly: the service's 140-character limit would reject this before the database.
        PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(setupClient));

        DataAccessException e = ourException(() -> writer.update(id, 1L, java.util.Set.of(PaymentOrderStatus.DRAFT),
                com.mongodb.client.model.Updates.set("remittance.unstructured", huge), "bob"));

        assertThat(e).isInstanceOfSatisfying(OrderValidationException.class,
                        validation -> assertThat(validation.reason()).isEqualTo(TOO_LARGE))
                .hasMessageContaining("is larger than the 16 MB BSON document limit");
        assertThat(e.getCause()).as("the driver's size error, not a field error")
                .isInstanceOf(BsonMaximumSizeExceededException.class);
        assertThat(version()).isEqualTo(1L);
    }

    /** E15: a value with no BSON codec is a programming error. It becomes IllegalArgumentException. */
    @Test
    void e15_valueWithoutCodecIsIllegalArgument() {
        PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(setupClient));

        Throwable thrown = catchThrowable(() -> writer.update(id, 1L, java.util.Set.of(PaymentOrderStatus.DRAFT),
                com.mongodb.client.model.Updates.set("remittance.unstructured", new Object()), "bob"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).isNotInstanceOf(MongoException.class);
        assertThat(version()).isEqualTo(1L);
    }

    /** E16: with the default timeoutMS, the driver retries until the deadline. Thus, the E3 failure has no effect. */
    @Test
    void e16_defaultTimeoutRidesOutRepeatedElectionErrors() {
        failUpdates(new Document("times", 2), new Document("errorCode", 10107));

        assertThat(service("").amend(id, 1L, remittance("e16"), "bob")).isEqualTo(2L);
        assertThat(version()).isEqualTo(2L);
    }

    /** E17: an authentication failure goes to the caller as it is. It is a configuration fault. */
    @Test
    void e17_authenticationFailureIsNotMapped() {
        String uri = MongoTestSupport.connectionString("appName=" + APP + "&authSource=admin")
                .replaceFirst("mongodb://", "mongodb://nobody:wrong@");
        MongoClient client = PaymentOrderClients.create(uri);
        clients.add(client);

        Throwable thrown = catchThrowable(
                () -> new PaymentOrderService(database(client)).amend(id, 1L, remittance("e17"), "bob"));

        assertThat(thrown).isInstanceOf(com.mongodb.MongoSecurityException.class);
        assertThat(version()).isEqualTo(1L);
    }

    /** E18: code 13 (Unauthorized) is not transient. A retry cannot correct it. */
    @Test
    void e18_unauthorizedIsNotAuthorisedMongoException() {
        failUpdates(new Document("times", 1), new Document("errorCode", 13));

        DataAccessException e = ourException(() -> service("").amend(id, 1L, remittance("e18"), "bob"));

        assertThat(e).isInstanceOf(NotAuthorisedMongoException.class).isNotInstanceOf(TransientDataAccessException.class);
        assertThat(e.getCause()).isInstanceOf(MongoException.class);
        assertThat(version()).isEqualTo(1L);
    }

    /** E19: a write-concern error with code 13 means that the primary applied the write. It is not "not authorised". */
    @Test
    void e19_writeConcernErrorWithCode13MayHaveBeenWritten() {
        failUpdates(new Document("times", 1), new Document("writeConcernError",
                new Document("code", 13).append("codeName", "Unauthorized").append("errmsg", "not authorized")));

        TransientDataAccessException e = transientFailure(() -> service("").amend(id, 1L, remittance("e19"), "bob"));

        assertThat(e).isNotInstanceOf(NotAuthorisedMongoException.class);
        assertThat(e).isInstanceOf(WriteNotConfirmedException.class);
        assertThat(version()).as("the primary applied it").isEqualTo(2L);
    }

    /** E20: the server sheds load (SystemOverloadedError), and the driver already retried. An attempt can have applied. */
    @Test
    void e20_systemOverloadedMayHaveBeenWritten() {
        failUpdates(new Document("times", 20), new Document("errorCode", 2).append("errorLabels",
                List.of(MongoException.SYSTEM_OVERLOADED_ERROR_LABEL, MongoException.RETRYABLE_ERROR_LABEL)));

        TransientDataAccessException e = transientFailure(
                () -> service(ONE_RETRY).amend(id, 1L, remittance("e20"), "bob"));

        assertThat(e).isInstanceOf(ServerOverloadedException.class);
        assertThat(((MongoException) e.getCause()).hasErrorLabel(MongoException.SYSTEM_OVERLOADED_ERROR_LABEL))
                .isTrue();
    }
}
