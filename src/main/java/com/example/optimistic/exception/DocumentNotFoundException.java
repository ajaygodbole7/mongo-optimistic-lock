package com.example.optimistic.exception;

/** No document has the {@code _id}. */
public non-sealed class DocumentNotFoundException extends OptimisticWriteException {

    public DocumentNotFoundException(Object id) {
        super(id, "Document " + id + " not found");
    }

    /** For a subclass with its own message. {@code cause} can be null. */
    protected DocumentNotFoundException(Object id, String message, Throwable cause) {
        super(id, message, cause);
    }
}
