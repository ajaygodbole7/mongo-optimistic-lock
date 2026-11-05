package com.example.optimistic;

import static com.example.optimistic.exception.SchemaValidationException.Reason.REJECTED_BY_VALIDATOR;
import static com.example.optimistic.exception.SchemaValidationException.Reason.TOO_LARGE;

import com.example.optimistic.exception.DataAccessException;
import com.example.optimistic.exception.DuplicateKeyException;
import com.example.optimistic.exception.NotAuthorisedMongoException;
import com.example.optimistic.exception.OperationTimeoutException;
import com.example.optimistic.exception.RetriedWriteException;
import com.example.optimistic.exception.SchemaValidationException;
import com.example.optimistic.exception.TransientDataAccessException;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoConnectionPoolClearedException;
import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.MongoNodeIsRecoveringException;
import com.mongodb.MongoNotPrimaryException;
import com.mongodb.MongoOperationTimeoutException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoServerException;
import com.mongodb.MongoSocketClosedException;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.MongoSocketReadException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.MongoSocketWriteException;
import com.mongodb.MongoSocketWriteTimeoutException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteConcernException;
import com.mongodb.MongoWriteException;
import java.util.Set;
import java.util.function.Supplier;
import org.bson.BsonMaximumSizeExceededException;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one database call for {@link OptimisticLockingWriter}, and changes a driver exception into a
 * {@link DataAccessException}. The one exception that it does not change is {@code MongoSecurityException}
 * (authentication failed). In each {@code switch}, the cases are tried from the top, so their sequence is the rule.
 */
final class MongoErrorTranslator {

    private static final Logger log = LoggerFactory.getLogger(MongoErrorTranslator.class);

    private static final int DOCUMENT_VALIDATION_FAILURE = 121;
    private static final int BSON_OBJECT_TOO_LARGE = 10334; // the document after an update would exceed 16 MB
    private static final int UNAUTHORIZED = 13;
    // A read error with one of these codes is a bad filter. A short allow-list: an unknown read error stays transient.
    private static final Set<Integer> BAD_QUERY = Set.of(2, 9); // BadValue, FailedToParse
    // What the driver throws (IllegalStateException) for an operation on a closed MongoClient.
    private static final String CLOSED_CLIENT_MESSAGE = "state should be: open";
    // Driver 5.13 has no public constants for these labels.
    private static final String NO_WRITES_PERFORMED = "NoWritesPerformed";
    private static final String RETRYABLE_WRITE_ERROR = "RetryableWriteError";

    private MongoErrorTranslator() {
    }

    /** What a database call does. This decides what {@code outcomeUnknown} can mean. */
    enum Kind { READ, INSERT, UPDATE }

    /** What a driver failure means for the write. */
    private enum Outcome { NOT_APPLIED, UNKNOWN, RETRIED, UNCLASSIFIED }

    /** Runs one database call, and translates what it throws. */
    static <T> T call(Object id, Kind kind, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException e) {
            throw translate(id, kind, e);
        }
    }

    private static RuntimeException translate(Object id, Kind kind, RuntimeException e) {
        return switch (e) {
            // A configuration fault (authentication): the caller deals with it.
            case MongoException m when securityCause(m) instanceof MongoSecurityException security -> security;
            case MongoException m -> translateDriver(id, kind, m);
            // Too large: the driver did not send it. An insert that failed stored nothing, so it has no id.
            case BsonMaximumSizeExceededException _ ->
                    new SchemaValidationException(idUnlessInsert(id, kind), TOO_LARGE, e);
            case CodecConfigurationException _ ->
                    new IllegalArgumentException("Value has no BSON codec: " + e.getMessage(), e);
            // The client is closed: nothing was sent.
            case IllegalStateException _ when CLOSED_CLIENT_MESSAGE.equals(e.getMessage()) ->
                    new TransientDataAccessException(id, false, e);
            default -> e; // a programming error: not translated
        };
    }

    /**
     * Classifies a driver exception (driver 5.13):
     * <ul>
     *   <li>A write error 121 (validator) or 10334 (the document would exceed 16 MB) is
     *       {@link SchemaValidationException}, and a write error 11000 is {@link DuplicateKeyException}. None is
     *       transient.</li>
     *   <li>A write-concern error means that the primary applied the write, whatever its code (also 13, E19). Thus,
     *       it comes before the code-13 case.</li>
     *   <li>Code 13 (Unauthorized) is {@link NotAuthorisedMongoException}: not transient.</li>
     *   <li>Any other write error without an error label is a bad request (for example 14, {@code $inc} on a string;
     *       40, conflicting paths; 2, a bad filter): {@link IllegalArgumentException}, nothing applied. The driver
     *       never retries because of a write error. If it retried after an earlier error, a retry of a write that
     *       had applied returns the same result, not a write error (the retryable-writes specification).</li>
     *   <li>A read error without an error label and with code 2 (BadValue) or 9 (FailedToParse) is a bad filter:
     *       {@link IllegalArgumentException}. All other read errors stay transient (for example a stepdown).</li>
     *   <li>A {@code timeoutMS} expiry is {@link OperationTimeoutException}, outcome unknown on a write: the driver
     *       can retry several times before the deadline, and an earlier attempt can have applied.</li>
     *   <li>All other failures are transient ({@link #transientFailure}).</li>
     * </ul>
     */
    private static RuntimeException translateDriver(Object id, Kind kind, MongoException e) {
        return switch (e) {
            case MongoWriteException w when w.getCode() == DOCUMENT_VALIDATION_FAILURE ->
                    new SchemaValidationException(idUnlessInsert(id, kind), REJECTED_BY_VALIDATOR, e);
            case MongoWriteException w when w.getCode() == BSON_OBJECT_TOO_LARGE ->
                    new SchemaValidationException(idUnlessInsert(id, kind), TOO_LARGE, e);
            case MongoWriteException w when w.getError().getCategory() == ErrorCategory.DUPLICATE_KEY ->
                    new DuplicateKeyException(id, e);
            case MongoWriteConcernException _ -> transientFailure(id, kind, e);
            case MongoServerException s when s.getCode() == UNAUTHORIZED ->
                    new NotAuthorisedMongoException(idUnlessInsert(id, kind), e); // a retry cannot help
            case MongoWriteException w when w.getErrorLabels().isEmpty() -> new IllegalArgumentException(
                    "The server rejected the write for document " + id + ": " + w.getError().getMessage(), e);
            case MongoServerException s when kind == Kind.READ && s.getErrorLabels().isEmpty()
                    && BAD_QUERY.contains(s.getCode()) -> new IllegalArgumentException(
                    "The server rejected the filter for document " + id + ": " + s.getMessage(), e);
            case MongoOperationTimeoutException _ -> new OperationTimeoutException(id, kind != Kind.READ, e);
            default -> transientFailure(id, kind, e);
        };
    }

    /** A transient failure. A failed read did not write, so its outcome is always known. */
    private static TransientDataAccessException transientFailure(Object id, Kind kind, MongoException e) {
        Outcome outcome = classify(e);
        if (outcome == Outcome.UNCLASSIFIED) {
            log.warn("Unclassified driver exception for document {}; treating as transient", id, e);
        }
        return switch (kind == Kind.READ ? Outcome.NOT_APPLIED : outcome) {
            case NOT_APPLIED -> new TransientDataAccessException(id, false, e);
            case RETRIED -> new RetriedWriteException(id, e);
            case UNKNOWN, UNCLASSIFIED -> new TransientDataAccessException(id, true, e);
        };
    }

    /**
     * What the failure means for the write:
     * <ul>
     *   <li>A write-concern error has the {@code RetryableWriteError} label, and so do network errors. Thus, both
     *       come before the label case.</li>
     *   <li>A command error that gets here comes from the driver's retry (retryable writes are always on). The
     *       driver reports only the error of the retry, unless it has {@code NoWritesPerformed}. Thus, the first
     *       attempt can have applied: {@link RetriedWriteException}, outcome unknown.</li>
     * </ul>
     */
    private static Outcome classify(MongoException e) {
        return switch (e) {
            case MongoWriteConcernException _ -> Outcome.UNKNOWN; // applied on the primary, not confirmed
            case MongoException m when m.hasErrorLabel(NO_WRITES_PERFORMED) -> Outcome.NOT_APPLIED;
            case MongoTimeoutException _, MongoSocketOpenException _, MongoConnectionPoolClearedException _ ->
                    Outcome.NOT_APPLIED; // never sent
            case MongoSocketReadException _, MongoSocketReadTimeoutException _, MongoSocketClosedException _,
                 MongoSocketWriteException _, MongoSocketWriteTimeoutException _, MongoInterruptedException _ ->
                    Outcome.UNKNOWN; // may have been sent; response lost
            // The server shed load and the driver already retried: an attempt can have applied.
            case MongoException m when m.hasErrorLabel(MongoException.SYSTEM_OVERLOADED_ERROR_LABEL) -> Outcome.UNKNOWN;
            case MongoNotPrimaryException _, MongoNodeIsRecoveringException _ -> Outcome.RETRIED;
            case MongoException m when m.hasErrorLabel(RETRYABLE_WRITE_ERROR) -> Outcome.RETRIED;
            default -> Outcome.UNCLASSIFIED;
        };
    }

    /** An insert that failed stored nothing, so its id is not reported. */
    private static Object idUnlessInsert(Object id, Kind kind) {
        return kind == Kind.INSERT ? null : id;
    }

    /** The MongoSecurityException in the cause chain, if any. */
    private static MongoSecurityException securityCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof MongoSecurityException security) {
                return security;
            }
        }
        return null;
    }
}
