package com.example.optimistic.exception;

/**
 * The request was not sent, so nothing was written: server selection or a pool checkout timed out, the pool was
 * cleared, the socket did not open, the client is closed, or the thread was interrupted during the wait between two
 * attempts of {@code updateWithRetry}. The caller can retry later; the version check still applies.
 */
public final class NotSentException extends NotWrittenException {

    public NotSentException(Object id, Throwable cause) {
        super(id, "not sent, not written", cause);
    }
}
