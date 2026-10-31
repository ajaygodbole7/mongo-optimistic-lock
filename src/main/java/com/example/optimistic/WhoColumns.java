package com.example.optimistic;

import com.mongodb.client.model.Updates;
import java.time.Instant;
import java.util.Set;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * The second job of {@link OptimisticLockingWriter}: the who-columns at the document root. They record the last writer,
 * not a history. The version orders writes; the dates come from the pod's clock and are for people.
 */
public final class WhoColumns {

    public static final String CREATED_DATE = "_createdDate";
    public static final String CREATED_BY = "_createdBy";
    public static final String LAST_MODIFIED_DATE = "_lastModifiedDate";
    public static final String LAST_MODIFIED_BY = "_lastModifiedBy";

    /** The four names. Only the writer sets these fields. */
    public static final Set<String> FIELDS = Set.of(CREATED_DATE, CREATED_BY, LAST_MODIFIED_DATE, LAST_MODIFIED_BY);

    private WhoColumns() {
    }

    /** For an insert: all four, from one instant. */
    static Document onInsert(Instant now, String user) {
        return new Document(CREATED_DATE, now)
                .append(CREATED_BY, user)
                .append(LAST_MODIFIED_DATE, now)
                .append(LAST_MODIFIED_BY, user);
    }

    /** For an update: only the last-modified pair. Nothing writes the created pair after the insert. */
    static Bson onUpdate(Instant now, String user) {
        return Updates.combine(Updates.set(LAST_MODIFIED_DATE, now), Updates.set(LAST_MODIFIED_BY, user));
    }
}
