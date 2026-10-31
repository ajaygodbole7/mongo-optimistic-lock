package com.example.optimistic.exception;

import com.example.optimistic.OptimisticLockingWriter;

/**
 * Root of the exceptions that callers of {@link OptimisticLockingWriter} get. The one raw driver exception that callers
 * can get is {@code MongoSecurityException} (authentication failed), which is not mapped.
 *
 * <p>It is not sealed, on purpose: each domain adds its own exceptions (for example {@code ShuttingDownException}).
 * Without a {@code module-info}, a sealed class can permit subclasses only in its own package.
 */
public abstract class DataAccessException extends RuntimeException {

    private final Object id;

    protected DataAccessException(Object id, String message, Throwable cause) {
        super(message, cause);
        this.id = id;
    }

    /** The {@code _id} of the document, or null if there is none (for example, an insert that was never stored). */
    public Object id() {
        return id;
    }
}
