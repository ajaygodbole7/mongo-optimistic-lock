package com.example.optimistic.exception;

import org.bson.Document;

/**
 * The document is at the expected version, but it does not match the precondition. {@link #current()} is the
 * document as the diagnosis read found it.
 */
public non-sealed class PreconditionFailedException extends OptimisticWriteException {

    private final transient Document current;

    public PreconditionFailedException(Object id, Document current) {
        this("Document " + id + " does not match the precondition", id, current, null);
    }

    /** For a subclass with its own message. {@code cause} can be null. */
    protected PreconditionFailedException(String message, Object id, Document current, Throwable cause) {
        super(id, message, cause);
        this.current = current;
    }

    public Document current() {
        return current;
    }
}
