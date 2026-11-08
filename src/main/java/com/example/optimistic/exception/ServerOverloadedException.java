package com.example.optimistic.exception;

/**
 * The server shed load ({@code SystemOverloadedError}), and the driver's own retries also failed. An attempt may have
 * applied. Reload before you retry, and retry later, not at once.
 */
public final class ServerOverloadedException extends MaybeWrittenException {

    public ServerOverloadedException(Object id, Throwable cause) {
        super(id, "server overloaded, may have been written", cause);
    }
}
