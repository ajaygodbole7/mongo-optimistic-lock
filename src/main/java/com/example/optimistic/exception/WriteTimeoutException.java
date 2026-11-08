package com.example.optimistic.exception;

/**
 * The operation deadline ({@code timeoutMS}) expired on a write. The driver can retry several times before the
 * deadline, so an earlier attempt can have applied. Reload before you retry.
 */
public final class WriteTimeoutException extends MaybeWrittenException {

    public WriteTimeoutException(Object id, Throwable cause) {
        super(id, "write timed out, may have been written", cause);
    }
}
