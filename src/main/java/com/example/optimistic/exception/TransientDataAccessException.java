package com.example.optimistic.exception;

/**
 * An infrastructure failure (election, network, timeout, pool exhaustion). The caller can retry later. When
 * {@link #outcomeUnknown()} is true, the write can have occurred. Then reload the document with
 * {@code ReadConcern.MAJORITY} before you retry. For an insert, {@link #id()} is the id to look up.
 */
public sealed class TransientDataAccessException extends DataAccessException
        permits RetriedWriteException, OperationTimeoutException {

    private final boolean outcomeUnknown;

    public TransientDataAccessException(Object id, boolean outcomeUnknown, Throwable cause) {
        super(id, "Transient failure for document " + id
                + (outcomeUnknown ? " (outcome unknown)" : " (not applied)") + ": " + cause.getMessage(), cause);
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean outcomeUnknown() {
        return outcomeUnknown;
    }
}
