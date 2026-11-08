package com.example.optimistic.exception;

/**
 * The server rejected the driver's retry of a write (not primary, node recovering, or a different retryable error).
 * The driver reports only the error of the retry. Thus, the first attempt can have applied. Reload before you retry.
 */
public final class RetriedWriteException extends MaybeWrittenException {

    public RetriedWriteException(Object id, Throwable cause) {
        super(id, "retry rejected, may have been written", cause);
    }
}
