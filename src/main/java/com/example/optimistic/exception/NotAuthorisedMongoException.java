package com.example.optimistic.exception;

/**
 * The server refused the operation (error code 13, Unauthorized). The database user has no permission for it. This
 * is not transient: a retry cannot correct it. The driver exception is the cause.
 */
public final class NotAuthorisedMongoException extends DataAccessException {

    public NotAuthorisedMongoException(Object id, Throwable cause) {
        super(id, "Not authorised for document " + id + ": " + cause.getMessage(), cause);
    }
}
