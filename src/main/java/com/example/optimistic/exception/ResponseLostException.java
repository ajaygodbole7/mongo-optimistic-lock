package com.example.optimistic.exception;

/**
 * The request may have reached the server, but the response did not arrive: the connection dropped, a socket read or
 * write failed or timed out, or the thread was interrupted. The driver throws the same interrupt exception before and
 * after the send, so the caller cannot tell them apart. The write may have happened. Reload before you retry.
 */
public final class ResponseLostException extends MaybeWrittenException {

    public ResponseLostException(Object id, Throwable cause) {
        super(id, "response lost, may have been written", cause);
    }
}
