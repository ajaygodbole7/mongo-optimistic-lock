package com.example.optimistic.exception;

/**
 * A unique index refused the write (error 11000): for example, an insert with an {@code _id} that exists. This is not
 * transient: a retry gets the same error. The driver exception is the cause.
 */
public final class DuplicateKeyException extends DataAccessException {

    public DuplicateKeyException(Object id, Throwable cause) {
        super(id, "Duplicate key for document " + id + ": " + cause.getMessage(), cause);
    }
}
