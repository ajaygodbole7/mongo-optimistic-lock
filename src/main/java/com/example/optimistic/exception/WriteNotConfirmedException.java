package com.example.optimistic.exception;

/**
 * A write-concern error: the primary applied the write, but a majority did not confirm it. A failover can still roll
 * it back. Reload with {@code ReadConcern.MAJORITY} before you retry.
 */
public final class WriteNotConfirmedException extends MaybeWrittenException {

    public WriteNotConfirmedException(Object id, Throwable cause) {
        super(id, "applied on the primary, not confirmed", cause);
    }
}
