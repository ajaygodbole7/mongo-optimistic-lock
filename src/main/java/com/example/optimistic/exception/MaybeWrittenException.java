package com.example.optimistic.exception;

/**
 * A transient failure where the write may have happened, for example because the response was lost. Reload the
 * document with {@code ReadConcern.MAJORITY} before you retry: if the version moved and the stored change is yours,
 * the write was successful. Do not calculate the change again and write it without a reload. A subtype names a known
 * reason.
 */
public sealed class MaybeWrittenException extends TransientDataAccessException
        permits WriteTimeoutException, RetriedWriteException, WriteNotConfirmedException, ResponseLostException,
                ServerOverloadedException {

    public MaybeWrittenException(Object id, Throwable cause) {
        this(id, "may have been written", cause);
    }

    protected MaybeWrittenException(Object id, String what, Throwable cause) {
        super(id, what, cause);
    }
}
