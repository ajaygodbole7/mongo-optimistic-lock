package com.example.optimistic.exception;

/**
 * The server rejected the driver's retry of a write (not primary, node recovering, or a different retryable error).
 * The driver reports only the error of the retry. Thus, the first attempt can have applied, and the outcome is always
 * unknown. Reload before you retry. Do not calculate the change again and write it without a reload.
 */
public final class RetriedWriteException extends TransientDataAccessException {

    public RetriedWriteException(Object id, Throwable cause) {
        super(id, true, cause);
    }
}
