package com.example.paymentorders;

import com.example.paymentorders.exception.ShuttingDownException;
import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Counts the service calls that are in progress, so that shutdown can drain them. It is in-process only, and it is
 * only for draining. It is not for the correctness of writes: the version check does that.
 */
final class InFlightGate {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition drained = lock.newCondition();
    private int inFlight;
    private boolean closed;

    /** Admits a call, or throws when the gate is closed. Call {@link #exit()} after each successful enter. */
    void enter() {
        lock.lock();
        try {
            if (closed) {
                throw new ShuttingDownException();
            }
            inFlight++;
        } finally {
            lock.unlock();
        }
    }

    void exit() {
        lock.lock();
        try {
            if (--inFlight == 0) {
                drained.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Refuses every later {@link #enter()}. */
    void close() {
        lock.lock();
        try {
            closed = true;
        } finally {
            lock.unlock();
        }
    }

    /** Waits until no call is in progress, for a maximum of {@code grace}. Returns true if the gate drained. */
    boolean awaitDrained(Duration grace) throws InterruptedException {
        lock.lock();
        try {
            long nanos = grace.toNanos();
            while (inFlight > 0) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = drained.awaitNanos(nanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }
}
