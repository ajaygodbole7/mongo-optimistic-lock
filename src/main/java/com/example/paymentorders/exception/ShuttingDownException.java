package com.example.paymentorders.exception;

import com.example.optimistic.exception.DataAccessException;

/** The service is shutting down, and it refused a new call. Nothing touched the database. */
public final class ShuttingDownException extends DataAccessException {

    public ShuttingDownException() {
        super(null, "Payment order service is shutting down", null);
    }
}
