package com.example.optimistic;

import static com.example.optimistic.MongoErrorTranslator.call;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

import com.example.optimistic.MongoErrorTranslator.Kind;
import com.example.optimistic.exception.DataAccessException;
import com.example.optimistic.exception.DocumentNotFoundException;
import com.example.optimistic.exception.DuplicateKeyException;
import com.example.optimistic.exception.NotSentException;
import com.example.optimistic.exception.NotWrittenException;
import com.example.optimistic.exception.OptimisticWriteException;
import com.example.optimistic.exception.PreconditionFailedException;
import com.example.optimistic.exception.RetriesExhaustedException;
import com.example.optimistic.exception.TransientDataAccessException;
import com.example.optimistic.exception.VersionConflictException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Updates;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.Objects;
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
 *   <li><b>Check:</b> if the update matches nothing, a read tells why: not found, a different version (a
 *       conflict), or a false precondition. If the version is the same and there is a precondition, a second read
 *       checks it.</li>
 *   <li><b>Retry:</b> {@link #update} never retries. {@link #updateWithRetry}, for system jobs only, reads again,
 *       calculates the change again, and retries a conflict after a backoff with full jitter.</li>
 * </ul>
 * Job 2, the who-columns ({@link WhoColumns}): an insert sets all four; an update sets the last-modified pair in the
 * same {@code updateOne} as the version.
 *
 * <p>The safety rules serve both jobs:
 * <ul>
 *   <li>The writer owns its system fields: {@code _id}, the who-columns, and the version path with its parents.</li>
 *   <li>It accepts only the operator allow-list.</li>
 *   <li>It pins majority writes and primary reads. Its own reads use the local read concern.</li>
 *   <li>{@link MongoErrorTranslator} changes each driver exception into a {@link DataAccessException}.</li>
 * </ul>
 *
 * @param <K> the type of {@code _id}
 */
public final class OptimisticLockingWriter<K> {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockingWriter.class);

    /** The subdocument that holds the version at the default path. It contains nothing else. */
    public static final String DOCUMENT_META = "_documentMeta";
    /** The version's name inside {@link #DOCUMENT_META}: a BSON {@code long}, 1 after the insert. */
    public static final String VERSION_FIELD = "version";
    private static final String DEFAULT_VERSION_PATH = DOCUMENT_META + "." + VERSION_FIELD;

    /** The update operators that a change can use. An allow-list fails closed. A block-list lets $rename through. */
    private static final Set<String> ALLOWED_OPERATORS = Set.of("$set", "$unset", "$inc");

    /** Root fields that only this writer sets, besides the version: {@code _id} and the who-columns. */
    private static final Set<String> ROOT_FIELDS = Stream.concat(Stream.of("_id"), WhoColumns.FIELDS.stream())
            .collect(Collectors.toUnmodifiableSet());

    private final MongoCollection<Document> collection;
    private final MongoCollection<Document> majorityReads; // reads that a failover cannot roll back
    private final InstantSource clock;
    private final String versionPath;

    public OptimisticLockingWriter(MongoCollection<Document> collection) {
        this(collection, InstantSource.system());
    }

    /**
     * {@code clock} supplies the who-column timestamps. Tests give a fixed one. Majority writes, primary reads and
     * the local read concern are pinned. The client and the URI cannot change them. Thus, an acknowledged write stays
     * after a failover, and the diagnosis read sees the primary's latest state, also if the URI sets a majority read
     * concern. {@link #read} is the one method that reads with {@code ReadConcern.MAJORITY}. Each document that the writer reads has its dates as
     * {@link Instant} ({@link #withInstantDates}), whatever client built the collection. One exception: a date inside
     * an array decodes as {@code java.util.Date}, because the driver's list decoding does not use this setting.
     */
    public OptimisticLockingWriter(MongoCollection<Document> collection, InstantSource clock) {
        this(collection, clock, DEFAULT_VERSION_PATH);
    }

    /**
     * Like {@link #OptimisticLockingWriter(MongoCollection, InstantSource)}, with the version at {@code versionPath}
     * (a dotted path, for example {@code "version"}). Every writer of a collection must use the same path: two writers
     * that check different paths do not see each other's writes.
     *
     * @throws NullPointerException if {@code collection} is null
     * @throws IllegalArgumentException if {@code clock} is null, if {@code collection} holds another class than
     *         {@link Document}, or if {@code versionPath} is null or blank, has an empty segment, has a segment that
     *         starts with {@code $} or is only digits, or starts with {@code _id} or a who-column
     */
    public OptimisticLockingWriter(MongoCollection<Document> collection, InstantSource clock, String versionPath) {
        MongoCollection<Document> source = Objects.requireNonNull(collection, "collection");
        this.clock = requireNonNull(clock, "clock");
        this.versionPath = checkedVersionPath(requireNonNull(versionPath, "versionPath"));
        // The generic type is erased: a raw cast can pass a collection of another document class.
        if (!Document.class.equals(source.getDocumentClass())) {
            throw new IllegalArgumentException("collection must be a MongoCollection<Document>, not one of "
                    + source.getDocumentClass());
        }
        this.collection = source
                .withCodecRegistry(withInstantDates(source.getCodecRegistry()))
                .withWriteConcern(WriteConcern.MAJORITY)
                .withReadPreference(ReadPreference.primary())
                .withReadConcern(ReadConcern.LOCAL); // the URI can set majority: a lagging read gives false conflicts
        this.majorityReads = this.collection.withReadConcern(ReadConcern.MAJORITY);
    }

    /**
     * Inserts {@code fields} with the caller's {@code id}, version 1 and all four who-columns. The caller makes the
     * id before the insert. Thus, if the outcome is unknown, the caller can look up the document.
     *
     * @throws IllegalArgumentException if {@code id} or {@code fields} is null, {@code user} is null or blank, or
     *         {@code fields} has a null key or contains a system field (also as the first segment of a dotted key)
     * @throws DuplicateKeyException if a document with the id exists (error 11000)
     */
    public void insert(K id, Document fields, String user) {
        requireNonNull(id, "id");
        requireUser(user);
        requireNonNull(fields, "fields");
        for (String key : fields.keySet()) {
            if (key == null) {
                throw new IllegalArgumentException("fields must not have a null key");
            }
            if (isSystemField(key)) {
                throw new IllegalArgumentException("System field not allowed in insert: " + key);
            }
        }
        Instant now = now(); // UTC; the driver drops the sub-millisecond part
        Document doc = new Document("_id", id);
        doc.putAll(fields);
        doc.putAll(nested(versionPath, 1L));
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
     * @throws IllegalArgumentException if {@code id} or {@code changes} is null, {@code user} is null or blank, or
     *         {@code changes} touches a system field or uses another operator
     * @throws DocumentNotFoundException if no document has the {@code id}
     * @throws VersionConflictException if the document is at another version
     * @throws PreconditionFailedException if the document is at the expected version but does not match
     *         {@code precondition}
     * @throws IllegalStateException if the diagnosis read finds a stored version that is not usable
     *         ({@link #readVersion})
     * @throws TransientDataAccessException if the database call failed. The write may have happened, unless the
     *         exception is a {@link NotWrittenException}.
     */
    public long update(K id, long expectedVersion, Bson precondition, Bson changes, String user) {
        requireNonNull(id, "id");
        requireUser(user);
        if (tryUpdate(id, expectedVersion, precondition, changes, user)) {
            return expectedVersion + 1;
        }
        throw diagnose(id, expectedVersion, precondition);
    }

    /** Does the versioned updateOne. Returns true if it matched. It does not do the diagnosis read. */
    private boolean tryUpdate(K id, long expectedVersion, Bson precondition, Bson changes, String user) {
        BsonDocument checked = checkedChanges(changes); // sent as checked: the caller's Bson is rendered once
        Bson filter = and(filter(id, precondition), eq(versionPath, expectedVersion));
        // The caller's changes come last: combine merges into the first operator document, so it changes ours.
        Bson update = Updates.combine(
                WhoColumns.onUpdate(now(), user),
                Updates.inc(versionPath, 1L),
                checked);
        return call(id, Kind.UPDATE, () -> collection.updateOne(filter, update)).getMatchedCount() == 1;
    }

    /**
     * System read-modify-write. Never use this for user writes.
     *
     * <p>Each attempt reads the document from the primary with the local read concern (a majority read lags and
     * causes conflicts). If the document does not exist, or does not match {@code precondition}, the method throws
     * before {@code recompute}. If not, {@code recompute} builds the change from the document. If {@code recompute} returns
     * {@code null}, there is nothing to change, and the method returns the version that it read. A version conflict
     * causes a retry after a full-jitter wait. All other failures stop the method.
     *
     * @return the new version, or the current version if {@code recompute} returned {@code null}
     * @throws IllegalArgumentException if {@code id}, {@code recompute} or {@code policy} is null, or {@code user} is
     *         null or blank. These checks run before the first read, so {@code recompute} does not run. It is also
     *         thrown if the change from {@code recompute} touches a system field or uses another operator.
     * @throws DocumentNotFoundException if the document does not exist. This comes before {@code recompute}, except
     *         on the last attempt, where {@link #update} diagnoses a failed write.
     * @throws PreconditionFailedException if the document does not match {@code precondition}. The same timing
     *         applies.
     * @throws RetriesExhaustedException when all {@code policy.maxAttempts()} attempts conflict; requeue the work
     * @throws IllegalStateException if a stored version is not usable ({@link #readVersion})
     * @throws TransientDataAccessException on an infrastructure failure. {@link NotSentException} also means an
     *         interrupt during the wait; the thread keeps its interrupt flag.
     * @throws RuntimeException the exception of {@code recompute}, unchanged
     */
    public long updateWithRetry(K id, Bson precondition, Function<Document, Bson> recompute, String user,
                                RetryPolicy policy) {
        requireNonNull(id, "id");
        requireNonNull(recompute, "recompute"); // before the first read: recompute can have side effects
        requireNonNull(policy, "policy");
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
            throw new NotSentException(id, interrupted); // the next attempt was not sent
        }
    }

    /**
     * The read after a zero match: it finds out why the update did not match. If the version is the same and there is
     * a precondition, a second read checks the precondition.
     */
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

    /** The clock is the caller's: its answer is checked too. A null date would be stored as a null who-column. */
    private Instant now() {
        Instant now = clock.instant();
        if (now == null) {
            throw new IllegalStateException("The clock returned null");
        }
        return now;
    }

    /** Like {@link java.util.Objects#requireNonNull(Object, String)}, with {@link IllegalArgumentException}. */
    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
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

    /**
     * Renders {@code changes} once, and rejects an operator outside the allow-list, an operator value that is not a
     * document, or a path that starts with a system field. Returns the rendering that it checked.
     */
    private BsonDocument checkedChanges(Bson changes) {
        if (changes == null) {
            throw new IllegalArgumentException("changes must not be null");
        }
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
            if (!(operator.getValue() instanceof BsonDocument fields)) {
                throw new IllegalArgumentException("Update operator value is not a document: " + operator.getKey());
            }
            for (String path : fields.keySet()) {
                if (isSystemField(path)) {
                    throw new IllegalArgumentException("System field not changeable: " + path);
                }
            }
        }
        return rendered;
    }

    /**
     * Reads a document with {@code ReadConcern.MAJORITY} from the primary. Thus, it does not return a write that the
     * database can still roll back. Use it to reload after a conflict or an outcome-unknown error.
     *
     * @return the document, or null if it does not exist
     * @throws IllegalArgumentException if {@code id} is null
     */
    public Document read(K id) {
        requireNonNull(id, "id");
        return call(id, Kind.READ, () -> majorityReads.find(eq("_id", id)).first());
    }

    /**
     * Startup check: throws if {@code db} is on a standalone server. The writer needs a replica set (or a sharded
     * cluster): retryable writes and majority concerns do not work on a standalone server, and the writer then runs
     * without any error. Call it once when the service starts. It sends one {@code hello} command, so a driver
     * exception (for example a timeout) reaches the caller as it is.
     *
     * @throws IllegalArgumentException if {@code db} is null
     * @throws IllegalStateException if the server is not a replica set member or a {@code mongos}
     */
    public static void requireReplicaSet(MongoDatabase db) {
        if (db == null) {
            throw new IllegalArgumentException("db must not be null");
        }
        Document hello = db.runCommand(new Document("hello", 1));
        boolean replicaSet = hello.get("setName") != null;
        boolean mongos = "isdbgrid".equals(hello.get("msg"));
        if (!replicaSet && !mongos) {
            throw new IllegalStateException("The server is a standalone mongod. OptimisticLockingWriter needs a "
                    + "replica set or a sharded cluster: retryable writes and majority concerns do not work here.");
        }
    }

    /**
     * {@code codecs}, but a {@code Document} decodes each BSON date as an {@link Instant} (UTC). A date inside an
     * array still decodes as {@code java.util.Date}. The caller's codecs still encode the values that it writes. But a
     * {@code Document} that the writer reads uses the driver's default type map (except dates): the caller's own
     * {@code Document} decoding settings do not apply to it.
     */
    public static CodecRegistry withInstantDates(CodecRegistry codecs) {
        requireNonNull(codecs, "codecs");
        return CodecRegistries.fromRegistries(
                CodecRegistries.fromProviders(new DocumentCodecProvider(
                        new BsonTypeClassMap(Map.of(BsonType.DATE_TIME, Instant.class)))),
                codecs);
    }

    /**
     * Reads the version at this writer's version path. It accepts a {@code long}, an {@code int}, or a whole
     * {@code double} (from mongosh or a migration) that fits a {@code long} exactly. It does not use a default value.
     *
     * @throws IllegalArgumentException if {@code doc} is null
     * @throws IllegalStateException if the version is missing, is of another type (also {@code Decimal128}), or is a
     *         {@code double} that is not whole or is outside the {@code long} range
     */
    public long readVersion(Document doc) {
        requireNonNull(doc, "doc");
        Object value = doc;
        for (String segment : versionPath.split("\\.")) {
            value = value instanceof Document d ? d.get(segment) : null;
        }
        return switch (value) {
            case Integer version -> version;
            case Long version -> version;
            case Double version -> wholeDouble(doc, version);
            case null -> throw new IllegalStateException("Document " + doc.get("_id") + " has no " + versionPath);
            default -> throw new IllegalStateException("Document " + doc.get("_id") + " has a version of type "
                    + value.getClass().getSimpleName() + ", not int, long or double: " + value);
        };
    }

    /** Exact: {@code longValue()} clips a number outside the long range, and a comparison as double hides the clip. */
    private static long wholeDouble(Document doc, double version) {
        try {
            return new BigDecimal(version).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) { // NaN, infinity, a fraction, or too large
            throw new IllegalStateException("Document " + doc.get("_id") + " has a version that is not a whole "
                    + "number that fits a long: " + version, e);
        }
    }

    /**
     * A key or change path is a system field if it starts with {@code _id} or a who-column, or if it is the version
     * path, one of its parents, or inside it: each of these would set the version.
     */
    private boolean isSystemField(String path) {
        return ROOT_FIELDS.contains(path.split("\\.", 2)[0])
                || path.equals(versionPath) || versionPath.startsWith(path + ".") || path.startsWith(versionPath + ".");
    }

    private static String checkedVersionPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("versionPath must not be null or blank");
        }
        String[] segments = path.split("\\.", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.startsWith("$") || segment.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("Invalid versionPath: " + path);
            }
        }
        if (ROOT_FIELDS.contains(segments[0])) {
            throw new IllegalArgumentException("versionPath must not start with a system field: " + path);
        }
        return path;
    }

    /** {@code {a: {b: value}}} for the path {@code a.b}. */
    private static Document nested(String path, Object value) {
        int dot = path.indexOf('.');
        return dot < 0 ? new Document(path, value)
                : new Document(path.substring(0, dot), nested(path.substring(dot + 1), value));
    }
}
