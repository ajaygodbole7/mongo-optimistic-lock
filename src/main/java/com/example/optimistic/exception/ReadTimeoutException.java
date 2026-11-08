package com.example.optimistic.exception;

/** The operation deadline ({@code timeoutMS}) expired on a read. A read does not write, so nothing was written. */
public final class ReadTimeoutException extends NotWrittenException {

    public ReadTimeoutException(Object id, Throwable cause) {
        super(id, "read timed out, not written", cause);
    }
}
