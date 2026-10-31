package com.example.optimistic.exception;

/**
 * {@code updateWithRetry} used all its attempts, and a different writer won each one. The last
 * {@link VersionConflictException} is the cause. A system job must requeue the work, because the change was not
 * applied.
 *
 * <p>It is not a {@link VersionConflictException}. Thus, a {@code catch (VersionConflictException e)} that means "a
 * user conflict: reload and let the user decide" cannot catch it by accident.
 */
public final class RetriesExhaustedException extends OptimisticWriteException {

    public RetriesExhaustedException(VersionConflictException last, int attempts) {
        super(last.id(), "Document " + last.id() + ": no attempts left after " + attempts
                + " version conflicts", last);
    }
}
