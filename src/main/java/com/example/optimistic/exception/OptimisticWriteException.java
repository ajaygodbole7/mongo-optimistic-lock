package com.example.optimistic.exception;

/**
 * The outcomes of an update that matched no document. The set is closed, so a caller can switch over it without a
 * {@code default}. {@link DocumentNotFoundException} and {@link PreconditionFailedException} are {@code non-sealed}:
 * a domain can extend them (for example {@code OrderNotFoundException}).
 */
public abstract sealed class OptimisticWriteException extends DataAccessException
        permits DocumentNotFoundException, VersionConflictException, PreconditionFailedException,
                RetriesExhaustedException {

    protected OptimisticWriteException(Object id, String message) {
        super(id, message, null);
    }

    protected OptimisticWriteException(Object id, String message, Throwable cause) {
        super(id, message, cause);
    }
}
