package com.example.paymentorders;

import com.example.paymentorders.exception.OrderValidationException.FieldError;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bson.types.Decimal128;

/**
 * The input checks of the service. They run before each write. They have the same business rules as the MongoDB
 * validator ({@link PaymentOrderSchema}). A test makes sure that {@link #REQUIRED_PATHS} is equal to the required
 * fields of the schema. The records already make sure of the types and the closed subtrees. Thus, the checks are
 * about presence.
 */
final class PaymentOrderChecks {

    /** The business fields that the schema requires. The service and the writer set the status and system fields. */
    static final Set<String> REQUIRED_PATHS = Set.of(
            "debtor", "debtor.account",
            "creditor", "creditor.name", "creditor.iban", "creditor.address", "creditor.address.country",
            "amount", "currency");

    private static final Pattern CURRENCY = Pattern.compile(PaymentOrderSchema.CURRENCY_PATTERN);
    private static final Pattern IBAN = Pattern.compile(PaymentOrderSchema.IBAN_PATTERN);

    private PaymentOrderChecks() {
    }

    static List<FieldError> checkNew(NewPaymentOrder order) {
        List<FieldError> errors = new ArrayList<>();
        checkDebtor(order.debtor(), errors);
        checkCreditor(order.creditor(), errors);
        if (required("amount", order.amount(), errors)) {
            checkAmount(order.amount(), errors);
        }
        checkCurrency(order.currency(), errors);
        checkRemittance(order.remittance(), errors);
        return errors;
    }

    /**
     * The acting user becomes {@code _lastModifiedBy} on each write, and also {@code _createdBy} on create (not on
     * an update). The schema requires that both are not empty.
     */
    static List<FieldError> checkUser(String user) {
        List<FieldError> errors = new ArrayList<>();
        if (user == null || user.isBlank()) {
            errors.add(new FieldError("user", "required"));
        }
        return errors;
    }

    /**
     * Checks an {@code amend}. The cases below are the amendable top-level fields. This is the single source for
     * them. All other keys (status, metadata, who-columns, dotted paths) are programming errors.
     */
    static List<FieldError> checkChanges(Map<String, ?> changes) {
        List<FieldError> errors = new ArrayList<>();
        changes.forEach((key, value) -> {
            switch (key) {
                case "debtor" -> {
                    if (value instanceof NewPaymentOrder.Debtor debtor) {
                        checkDebtor(debtor, errors);
                    } else {
                        wrongType(key, value, NewPaymentOrder.Debtor.class, errors);
                    }
                }
                case "creditor" -> {
                    if (value instanceof NewPaymentOrder.Creditor creditor) {
                        checkCreditor(creditor, errors);
                    } else {
                        wrongType(key, value, NewPaymentOrder.Creditor.class, errors);
                    }
                }
                case "amount" -> {
                    if (value instanceof BigDecimal amount) {
                        checkAmount(amount, errors);
                    } else {
                        wrongType(key, value, BigDecimal.class, errors);
                    }
                }
                case "currency" -> {
                    if (value instanceof String currency) {
                        checkCurrency(currency, errors);
                    } else {
                        wrongType(key, value, String.class, errors);
                    }
                }
                case "remittance" -> {
                    if (value instanceof NewPaymentOrder.Remittance remittance) {
                        checkRemittance(remittance, errors);
                    } else {
                        wrongType(key, value, NewPaymentOrder.Remittance.class, errors);
                    }
                }
                default -> throw new IllegalArgumentException("Field not amendable: " + key);
            }
        });
        return errors;
    }

    static List<FieldError> checkAddress(NewPaymentOrder.Address address) {
        List<FieldError> errors = new ArrayList<>();
        checkAddress("creditor.address", address, errors);
        return errors;
    }

    private static void checkDebtor(NewPaymentOrder.Debtor debtor, List<FieldError> errors) {
        if (required("debtor", debtor, errors)) {
            requiredText("debtor.account", debtor.account(), errors);
            optionalText("debtor.name", debtor.name(), errors);
        }
    }

    private static void checkCreditor(NewPaymentOrder.Creditor creditor, List<FieldError> errors) {
        if (required("creditor", creditor, errors)) {
            if (requiredText("creditor.name", creditor.name(), errors)) {
                maxLength("creditor.name", creditor.name(), errors);
            }
            if (requiredText("creditor.iban", creditor.iban(), errors)
                    && !IBAN.matcher(creditor.iban()).matches()) {
                errors.add(new FieldError("creditor.iban", "must have the IBAN format"));
            }
            checkAddress("creditor.address", creditor.address(), errors);
        }
    }

    private static void checkAddress(String path, NewPaymentOrder.Address address, List<FieldError> errors) {
        if (required(path, address, errors)) {
            requiredText(path + ".country", address.country(), errors);
        }
    }

    private static void checkCurrency(String currency, List<FieldError> errors) {
        if (requiredText("currency", currency, errors) && !CURRENCY.matcher(currency).matches()) {
            errors.add(new FieldError("currency", "must be 3 capital letters"));
        }
    }

    private static void checkRemittance(NewPaymentOrder.Remittance remittance, List<FieldError> errors) {
        if (remittance != null) {
            optionalText("remittance.reference", remittance.reference(), errors);
            optionalText("remittance.unstructured", remittance.unstructured(), errors);
        }
    }

    /** Records an error if a required string is null or empty. Returns true if it is present and not empty. */
    private static boolean requiredText(String path, String value, List<FieldError> errors) {
        if (!required(path, value, errors)) {
            return false;
        }
        if (value.isEmpty()) {
            errors.add(new FieldError(path, "must not be empty"));
            return false;
        }
        return true;
    }

    /** An optional name or remittance text: if present, not empty and not too long. */
    private static void optionalText(String path, String value, List<FieldError> errors) {
        if (value == null) {
            return;
        }
        if (value.isEmpty()) {
            errors.add(new FieldError(path, "must not be empty"));
        } else {
            maxLength(path, value, errors);
        }
    }

    /** Counts characters (code points), as the $jsonSchema maxLength does; String.length() counts UTF-16 units. */
    private static void maxLength(String path, String value, List<FieldError> errors) {
        if (value.codePointCount(0, value.length()) > PaymentOrderSchema.MAX_TEXT_LENGTH) {
            errors.add(new FieldError(path, "must have at most " + PaymentOrderSchema.MAX_TEXT_LENGTH + " characters"));
        }
    }

    /** The amount must be more than 0, and fit Decimal128 (at most 34 significant digits). */
    private static void checkAmount(BigDecimal amount, List<FieldError> errors) {
        if (amount.signum() <= 0) {
            errors.add(new FieldError("amount", "must be more than 0"));
            return;
        }
        try {
            new Decimal128(amount);
        } catch (NumberFormatException _) {
            errors.add(new FieldError("amount", "does not fit Decimal128 (at most 34 significant digits)"));
        }
    }

    /** Records an error if {@code value} is null. Returns true if the value is present. */
    private static boolean required(String path, Object value, List<FieldError> errors) {
        if (value == null) {
            errors.add(new FieldError(path, "required"));
            return false;
        }
        return true;
    }

    /** Records why {@code value} cannot be used: it is null, or it is not a {@code type}. */
    private static void wrongType(String path, Object value, Class<?> type, List<FieldError> errors) {
        errors.add(new FieldError(path, value == null ? "must not be null" : "must be a " + type.getSimpleName()));
    }
}
