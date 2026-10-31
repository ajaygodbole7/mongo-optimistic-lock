package com.example.optimistic.exception;

/** A different writer changed the document after the caller read it. Reload, then decide again. */
public final class VersionConflictException extends OptimisticWriteException {

    private final long expectedVersion;
    private final long actualVersion;

    public VersionConflictException(Object id, long expectedVersion, long actualVersion) {
        super(id, "Document " + id + ": expected version " + expectedVersion
                + " but found " + actualVersion);
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long actualVersion() {
        return actualVersion;
    }
}
