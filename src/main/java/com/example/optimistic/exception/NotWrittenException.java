package com.example.optimistic.exception;

/**
 * A transient failure where nothing was written: the request was not sent, the operation was a read, or the thread
 * was interrupted before a write. The caller can send the same write again. A subtype names a known reason.
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
