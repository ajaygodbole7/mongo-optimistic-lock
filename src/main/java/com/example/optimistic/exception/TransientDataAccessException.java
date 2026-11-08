package com.example.optimistic.exception;

/**
 * An infrastructure failure (election, network, timeout, pool exhaustion). The caller can retry later. The subtype
 * tells what happened to the data:
 * <ul>
 *   <li>{@link NotWrittenException}: nothing was written. The caller can send the same write again.</li>
 *   <li>{@link MaybeWrittenException}: the write may have happened. Reload the document with
 *       {@code ReadConcern.MAJORITY} before you retry.</li>
 * </ul>
 * For an insert, {@link #id()} is the id to look up.
 */
public abstract sealed class TransientDataAccessException extends DataAccessException
        permits NotWrittenException, MaybeWrittenException {

    protected TransientDataAccessException(Object id, String what, Throwable cause) {
        super(id, "Transient failure for document " + id + " (" + what + "): " + cause.getMessage(), cause);
    }
}
