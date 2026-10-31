package com.example.paymentorders.exception;

import com.example.optimistic.exception.SchemaValidationException;
import java.util.List;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;

/**
 * The order is not valid. The write did not occur. Usually, the service's own checks throw it before they touch the
 * database, and {@link #errors()} lists all problems. If the server's validator rejects a write (code 121), the
 * service checks and the schema do not agree. Then {@link #errors()} is empty, and the driver exception is the cause.
 */
public final class OrderValidationException extends SchemaValidationException {

    /** One problem, with its dotted path, for example {@code creditor.address: required}. */
    public record FieldError(String path, String message) {
        @Override
        public String toString() {
            return path + ": " + message;
        }
    }

    private final List<FieldError> errors;

    /** The service's checks throw this. {@code id} is null for a create. */
    public OrderValidationException(ObjectId id, List<FieldError> errors) {
        super("Payment order " + (id == null ? "(new)" : id) + " " + Reason.FIELD_ERRORS.text() + ": "
                + errors.stream().map(FieldError::toString).collect(Collectors.joining(", ")),
                id, Reason.FIELD_ERRORS, null);
        this.errors = List.copyOf(errors);
    }

    /**
     * For a write that the database refused: the validator rejected it, or it is larger than the BSON size limit
     * ({@code reason}). {@code cause} is the driver exception.
     */
    public OrderValidationException(ObjectId id, Reason reason, Throwable cause) {
        super("Payment order " + (id == null ? "(new)" : id) + " " + reason.text() + ": " + cause.getMessage(),
                id, reason, cause);
        this.errors = List.of();
    }

    public List<FieldError> errors() {
        return errors;
    }
}
