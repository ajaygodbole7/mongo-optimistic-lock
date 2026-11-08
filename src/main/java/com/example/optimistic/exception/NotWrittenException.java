package com.example.optimistic.exception;

/**
 * A transient failure where nothing was written: the request was not sent, the operation was a read, or the server
 * reported that it performed no write. The caller can retry later; the version check still applies. A subtype names a
 * known reason.
 */
public sealed class NotWrittenException extends TransientDataAccessException
        permits ReadTimeoutException, NotSentException {

    public NotWrittenException(Object id, Throwable cause) {
        this(id, "not written", cause);
    }

    protected NotWrittenException(Object id, String what, Throwable cause) {
        super(id, what, cause);
    }
}
