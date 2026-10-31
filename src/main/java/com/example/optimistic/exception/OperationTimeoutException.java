package com.example.optimistic.exception;

/**
 * The operation deadline ({@code timeoutMS}) expired. On a write, the outcome is always unknown. The driver can retry
 * several times before the deadline, so an earlier attempt can have applied. Reload before you retry. On a read,
 * nothing was written.
 */
public final class OperationTimeoutException extends TransientDataAccessException {

    public OperationTimeoutException(Object id, boolean outcomeUnknown, Throwable cause) {
        super(id, outcomeUnknown, cause);
    }
}
