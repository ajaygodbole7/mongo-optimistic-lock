package com.example.optimistic.exception;

/**
 * The write did not occur because the document is not valid. {@link #reason()} tells why. For a size or validator
 * failure, the cause is the actual driver exception.
 */
public class SchemaValidationException extends DataAccessException {

    /** Why the document is not valid. The text is the middle of the message: "Document 42 &lt;text&gt;: ...". */
    public enum Reason {
        /** Larger than the BSON limit: the driver did not send it, or the server refused the result (code 10334). */
        TOO_LARGE("is larger than the 16 MB BSON document limit"),
        /** The server's {@code $jsonSchema} validator refused it (code 121). */
        REJECTED_BY_VALIDATOR("was rejected by the validator"),
        /**
         * The application's own input checks found problems, before the database. The generic writer never throws
         * this reason; a domain subclass uses it (for example {@code OrderValidationException} with field errors).
         */
        FIELD_ERRORS("has field errors");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    private final Reason reason;

    public SchemaValidationException(Object id, Reason reason, Throwable cause) {
        this("Document " + (id == null ? "(new)" : id) + " " + reason.text() + ": " + cause.getMessage(),
                id, reason, cause);
    }

    /** For a subclass with its own message. {@code cause} can be null. */
    protected SchemaValidationException(String message, Object id, Reason reason, Throwable cause) {
        super(id, message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
