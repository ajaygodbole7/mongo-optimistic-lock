package com.example.optimistic;

import static com.example.optimistic.MongoErrorTranslator.call;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

import com.example.optimistic.MongoErrorTranslator.Kind;
import com.example.optimistic.exception.DataAccessException;
import com.example.optimistic.exception.DocumentNotFoundException;
import com.example.optimistic.exception.DuplicateKeyException;
import com.example.optimistic.exception.OptimisticWriteException;
import com.example.optimistic.exception.PreconditionFailedException;
import com.example.optimistic.exception.RetriesExhaustedException;
import com.example.optimistic.exception.TransientDataAccessException;
import com.example.optimistic.exception.VersionConflictException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Updates;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonType;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.codecs.BsonTypeClassMap;
import org.bson.codecs.DocumentCodecProvider;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optimistic locking and who-columns for one MongoDB collection, with the safety rules that keep both correct. All
 * writes to the collection must go through it.
 *
 * <p>Job 1, optimistic locking:
 * <ul>
 *   <li><b>Write:</b> an insert starts at version 1. An update is one {@code updateOne} that filters on {@code _id}
 *       and the expected version, and does {@code $inc} of the version in the same atomic operation.</li>
 *   <li><b>Check:</b> if the update matches nothing, one read tells why: not found, a different version (a
 *       conflict), or a false precondition.</li>
 *   <li><b>Retry:</b> {@link #update} never retries. {@link #updateWithRetry}, for system jobs only, reads again,
 *       calculates the change again, and retries a conflict after a backoff with full jitter.</li>
 * </ul>
 * Job 2, the who-columns ({@link WhoColumns}): an insert sets all four; an update sets the last-modified pair in the
 * same {@code updateOne} as the version.
 *
 * <p>The safety rules serve both jobs: the writer owns six field names ({@link #SYSTEM_FIELDS}), it accepts only the
 * operator allow-list, it pins majority writes and primary reads, and {@link MongoErrorTranslator} changes each driver
 * exception into a {@link DataAccessException}.
 *
 * @param <K> the type of {@code _id}
 */
public final class OptimisticLockingWriter<K> {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockingWriter.class);

    /** The subdocument that holds the version. It contains nothing else. */
    public static final String DOCUMENT_META = "_documentMeta";
    /** The version's name inside {@link #DOCUMENT_META}: a BSON {@code long}, 1 after the insert. */
    public static final String VERSION_FIELD = "version";
    private static final String VERSION = DOCUMENT_META + "." + VERSION_FIELD;

    /** The update operators that a change can use. An allow-list fails closed. A block-list lets $rename through. */
    private static final Set<String> ALLOWED_OPERATORS = Set.of("$set", "$unset", "$inc");

    /** The six fields that only this writer sets: {@code _id}, the version's root and the who-columns. */
    public static final Set<String> SYSTEM_FIELDS = Stream.concat(Stream.of("_id", DOCUMENT_META),
            WhoColumns.FIELDS.stream()).collect(Collectors.toUnmodifiableSet());

    private final MongoCollection<Document> collection;
    private final MongoCollection<Document> majorityReads; // reads that a failover cannot roll back
    private final InstantSource clock;

    public OptimisticLockingWriter(MongoCollection<Document> collection) {
        this(collection, InstantSource.system());
    }

    /**
     * {@code clock} supplies the who-column timestamps. Tests give a fixed one. Majority writes and primary reads
     * are pinned. The client and the URI cannot change them. Thus, an acknowledged write stays after a failover,
     * and the diagnosis read is current. Each document that the writer reads has its dates as {@link Instant}
     * ({@link #withInstantDates}), whatever client built the collection. One exception: a date inside an array
     * decodes as {@code java.util.Date}, because the driver's list decoding does not use this setting.
     */
    public OptimisticLockingWriter(MongoCollection<Document> collection, InstantSource clock) {
        this.collection = collection
                .withCodecRegistry(withInstantDates(collection.getCodecRegistry()))
                .withWriteConcern(WriteConcern.MAJORITY)
                .withReadPreference(ReadPreference.primary());
        this.majorityReads = this.collection.withReadConcern(ReadConcern.MAJORITY);
        this.clock = clock;
    }

    /**
     * Inserts {@code fields} with the caller's {@code id}, version 1 and all four who-columns. The caller makes the
     * id before the insert. Thus, if the outcome is unknown, the caller can look up the document.
     *
     * @throws IllegalArgumentException if {@code id} is null, {@code user} is null or blank, or {@code fields}
     *         contains a system field (also as the first segment of a dotted key)
     * @throws DuplicateKeyException if a document with the id exists (error 11000)
     */
    public void insert(K id, Document fields, String user) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        requireUser(user);
        for (String key : fields.keySet()) {
            if (SYSTEM_FIELDS.contains(key.split("\\.", 2)[0])) {
                throw new IllegalArgumentException("System field not allowed in insert: " + key);
            }
        }
        Instant now = clock.instant(); // UTC; the driver drops the sub-millisecond part
        Document doc = new Document("_id", id);
        doc.putAll(fields);
        doc.append(DOCUMENT_META, new Document(VERSION_FIELD, 1L));
        doc.putAll(WhoColumns.onInsert(now, user));
        call(id, Kind.INSERT, () -> collection.insertOne(doc));
    }

    /**
     * Applies {@code changes} ({@code $set}, {@code $unset}, {@code $inc}) if the document is at
     * {@code expectedVersion} and matches {@code precondition}. This method sets the last-modified pair and the
     * version itself.
     *
     * @param precondition an extra filter (a status rule, a guard, a shard key), or null for none
     * @return the new version, {@code expectedVersion + 1}
     * @throws IllegalArgumentException if {@code changes} touches a system field or uses another operator
     * @throws DocumentNotFoundException, VersionConflictException, PreconditionFailedException if no document matches
     */
    public long update(K id, long expectedVersion, Bson precondition, Bson changes, String user) {
        requireUser(user);
        if (tryUpdate(id, expectedVersion, precondition, changes, user)) {
            return expectedVersion + 1;
        }
        throw diagnose(id, expectedVersion, precondition);
    }

    /** Does the versioned updateOne. Returns true if it matched. It does not do the diagnosis read. */
    private boolean tryUpdate(K id, long expectedVersion, Bson precondition, Bson changes, String user) {
        rejectSystemFields(changes);
        Bson filter = and(filter(id, precondition), eq(VERSION, expectedVersion));
        // The caller's changes come last: combine merges into the first operator document, so it changes ours.
        Bson update = Updates.combine(
                WhoColumns.onUpdate(clock.instant(), user),
                Updates.inc(VERSION, 1L),
                changes);
        return call(id, Kind.UPDATE, () -> collection.updateOne(filter, update)).getMatchedCount() == 1;
    }

    /**
     * System read-modify-write. Never use this for user writes.
     *
     * <p>Each attempt reads the document from the primary (default read concern: a majority read lags and causes
     * conflicts). If the document does not exist, or does not match {@code precondition}, the method throws before
     * {@code recompute}. If not, {@code recompute} builds the change from the document. If {@code recompute} returns
     * {@code null}, there is nothing to change, and the method returns the version that it read. A version conflict
     * causes a retry after a full-jitter wait. All other failures stop the method.
     *
     * @return the new version, or the current version if {@code recompute} returned {@code null}
     * @throws DocumentNotFoundException, PreconditionFailedException before {@code recompute}
     * @throws RetriesExhaustedException when all {@code policy.maxAttempts()} attempts conflict; requeue the work
     * @throws TransientDataAccessException on an infrastructure failure, or on an interrupt during the wait
     */
    public long updateWithRetry(K id, Bson precondition, Function<Document, Bson> recompute, String user,
                                RetryPolicy policy) {
        requireUser(user);
        for (int attempt = 1; ; attempt++) {
            // A local read: a majority read lags the primary and causes conflicts on hot documents.
            Document current = call(id, Kind.READ, () -> collection.find(filter(id, precondition)).first());
            if (current == null) {
                throw notFoundOrPreconditionFailed(id);
            }
            long version = readVersion(current);
            Bson changes = recompute.apply(current);
            if (changes == null) {
                return version; // nothing to change
            }
            if (attempt >= policy.maxAttempts()) {
                try {
                    return update(id, version, precondition, changes, user); // the last attempt diagnoses a failure
                } catch (VersionConflictException e) {
                    throw new RetriesExhaustedException(e, attempt);
                }
            }
            if (tryUpdate(id, version, precondition, changes, user)) {
                return version + 1;
            }
            // A conflict. The next attempt's read also shows a missing document or a false precondition.
            backoff(id, policy, attempt);
        }
    }

    /**
     * The wait after a conflict: exponential backoff with full jitter (a random time between 0 and
     * {@link RetryPolicy#maxSleep}). {@link RetryPolicy} explains why. An interrupt is a cancellation: nothing
     * applied.
     */
    private static void backoff(Object id, RetryPolicy policy, int attempt) {
        try {
            long upperNanos = policy.maxSleep(attempt).toNanos();
            Thread.sleep(Duration.ofNanos(ThreadLocalRandom.current().nextLong(upperNanos + 1)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TransientDataAccessException(id, false, interrupted);
        }
    }

    /** The one read after a zero match. It finds out why the update did not match. */
    private OptimisticWriteException diagnose(K id, long expectedVersion, Bson precondition) {
        Document current = call(id, Kind.READ, () -> collection.find(eq("_id", id)).first());
        if (current == null) {
            return new DocumentNotFoundException(id);
        }
        long actualVersion = readVersion(current);
        if (actualVersion != expectedVersion) {
            return new VersionConflictException(id, expectedVersion, actualVersion);
        }
        // Same version, no match: the precondition failed. Only after a rollback or a race does the document match
        // it now; one filtered read tells, so that a retryable conflict is not reported as a final failure.
        if (precondition != null
                && call(id, Kind.READ, () -> collection.find(filter(id, precondition)).first()) == null) {
            return new PreconditionFailedException(id, current);
        }
        log.warn("Document {} matched nothing at version {}; reporting a conflict", id, expectedVersion);
        return new VersionConflictException(id, expectedVersion, actualVersion);
    }

    /** After a read with the precondition found nothing: a read by {@code _id} alone tells which case it is. */
    private OptimisticWriteException notFoundOrPreconditionFailed(K id) {
        Document current = call(id, Kind.READ, () -> collection.find(eq("_id", id)).first());
        return current == null ? new DocumentNotFoundException(id) : new PreconditionFailedException(id, current);
    }

    /** The user becomes a who-column, so it must name someone. */
    private static void requireUser(String user) {
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("user must not be null or blank");
        }
    }

    private static Bson filter(Object id, Bson precondition) {
        return precondition == null ? eq("_id", id) : and(eq("_id", id), precondition);
    }

    /** Rejects a change with an operator outside the allow-list, or with a path that starts with a system field. */
    private void rejectSystemFields(Bson changes) {
        BsonDocument rendered;
        try {
            rendered = changes.toBsonDocument(BsonDocument.class, collection.getCodecRegistry());
        } catch (CodecConfigurationException e) {
            throw new IllegalArgumentException("Value has no BSON codec: " + e.getMessage(), e);
        }
        for (Map.Entry<String, BsonValue> operator : rendered.entrySet()) {
            if (!ALLOWED_OPERATORS.contains(operator.getKey())) {
                throw new IllegalArgumentException("Update operator not allowed: " + operator.getKey());
            }
            if (operator.getValue() instanceof BsonDocument fields) {
                for (String path : fields.keySet()) {
                    if (SYSTEM_FIELDS.contains(path.split("\\.", 2)[0])) {
                        throw new IllegalArgumentException("System field not changeable: " + path);
                    }
                }
            }
        }
    }

    /**
     * Reads a document with {@code ReadConcern.MAJORITY} from the primary. Thus, it does not return a write that the
     * database can still roll back. Use it to reload after a conflict or an outcome-unknown error.
     *
     * @return the document, or null if it does not exist
     */
    public Document read(K id) {
        return call(id, Kind.READ, () -> majorityReads.find(eq("_id", id)).first());
    }

    /**
     * {@code codecs}, but a {@code Document} decodes each BSON date as an {@link Instant} (UTC). A date inside an
     * array still decodes as {@code java.util.Date}. The caller's codecs still encode the values that it writes. But a
     * {@code Document} that the writer reads uses the driver's default type map (except dates): the caller's own
     * {@code Document} decoding settings do not apply to it.
     */
    public static CodecRegistry withInstantDates(CodecRegistry codecs) {
        return CodecRegistries.fromRegistries(
                CodecRegistries.fromProviders(new DocumentCodecProvider(
                        new BsonTypeClassMap(Map.of(BsonType.DATE_TIME, Instant.class)))),
                codecs);
    }

    /**
     * Reads {@code _documentMeta.version}. If it is missing or not a whole number, it throws. It does not use a
     * default value. An {@code int} or a whole {@code double} (from mongosh or a migration) is accepted.
     */
    public static long readVersion(Document doc) {
        if (doc.get(DOCUMENT_META) instanceof Document meta && meta.get(VERSION_FIELD) instanceof Number version) {
            if (version.doubleValue() != version.longValue()) {
                throw new IllegalStateException("Document " + doc.get("_id") + " has a version that is not a whole "
                        + "number: " + version);
            }
            return version.longValue();
        }
        throw new IllegalStateException("Document " + doc.get("_id") + " has no _documentMeta.version");
    }
}
