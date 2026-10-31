package com.example.paymentorders;

import static com.example.paymentorders.PaymentOrderStatus.CANCELLED;
import static com.example.paymentorders.PaymentOrderStatus.DRAFT;
import static com.example.paymentorders.PaymentOrderStatus.SUBMITTED;

import com.example.optimistic.RetryPolicy;
import com.example.paymentorders.exception.OrderValidationException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Updates;
import java.math.BigDecimal;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

/**
 * The payment order use cases. The input is checked first ({@link PaymentOrderChecks}). An invalid order throws
 * {@link OrderValidationException} with all problems, before the database is touched. All writes go through
 * {@link PaymentOptimisticWriter}. Nothing is retried.
 */
public final class PaymentOrderService {

    private final PaymentOptimisticWriter writer;
    private final InFlightGate gate; // null when not owned by a PaymentOrderRuntime

    public PaymentOrderService(MongoDatabase db) {
        this(db, InstantSource.system());
    }

    /** {@code clock} supplies the who-column timestamps. Tests give a fixed one. */
    public PaymentOrderService(MongoDatabase db, InstantSource clock) {
        this(db, clock, null);
    }

    /** For {@link PaymentOrderRuntime}: each call runs inside {@code gate}, so that shutdown can drain the calls. */
    PaymentOrderService(MongoDatabase db, InstantSource clock, InFlightGate gate) {
        this.writer = new PaymentOptimisticWriter(db.getCollection(PaymentOrderSchema.COLLECTION), clock);
        this.gate = gate;
    }

    public ObjectId create(NewPaymentOrder order, String user) {
        return gated(() -> {
            rejectIfInvalid(null, user, PaymentOrderChecks.checkNew(order));
            return writer.insert(toFields(order), user);
        });
    }

    /** The document fields for a new order, without any checks. */
    static Document toFields(NewPaymentOrder order) {
        Document fields = new Document(PaymentOrderSchema.STATUS, DRAFT.name());
        putIfPresent(fields, "debtor", toBson(order.debtor()));
        putIfPresent(fields, "creditor", toBson(order.creditor()));
        putIfPresent(fields, "amount", toBson(order.amount()));
        putIfPresent(fields, "currency", order.currency());
        putIfPresent(fields, "remittance", toBson(order.remittance()));
        return fields;
    }

    /**
     * UC1: {@code $set} the given top-level fields on a DRAFT order. A subtree value replaces the whole subtree.
     * {@link PaymentOrderChecks#checkChanges} defines the amendable fields. All other keys are rejected.
     */
    public long amend(ObjectId id, long expectedVersion, Map<String, ?> changes, String user) {
        return gated(() -> {
            if (changes.isEmpty()) {
                throw new IllegalArgumentException("No changes given");
            }
            rejectIfInvalid(id, user, PaymentOrderChecks.checkChanges(changes));
            Bson[] sets = changes.entrySet().stream()
                    .map(e -> Updates.set(e.getKey(), toBson(e.getValue())))
                    .toArray(Bson[]::new);
            return writer.update(id, expectedVersion, Set.of(DRAFT), Updates.combine(sets), user);
        });
    }

    /** UC1: replaces only {@code creditor.address} on a DRAFT order. The other fields of the creditor do not change. */
    public long updateCreditorAddress(ObjectId id, long expectedVersion, NewPaymentOrder.Address address,
                                      String user) {
        return gated(() -> {
            rejectIfInvalid(id, user, PaymentOrderChecks.checkAddress(address));
            return writer.update(id, expectedVersion, Set.of(DRAFT),
                    Updates.set("creditor.address", toBson(address)), user);
        });
    }

    /**
     * Reads an order with majority read concern from the primary. Use it to reload after a conflict or an
     * outcome-unknown error. It returns the current version, which you send with the next write.
     */
    public Optional<Document> get(ObjectId id) {
        return gated(() -> Optional.ofNullable(writer.read(id)));
    }

    /**
     * System read-modify-write ({@link PaymentOptimisticWriter#updateWithRetry}) for system jobs, inside the shutdown
     * gate and on this service's client. A system job does not need a second {@code MongoClient}. Do not use it for
     * user writes.
     */
    public long updateWithRetry(ObjectId id, Set<PaymentOrderStatus> allowed, Function<Document, Bson> recompute,
                                String user, RetryPolicy policy) {
        return gated(() -> {
            rejectIfInvalid(id, user, List.of());
            return writer.updateWithRetry(id, allowed, recompute, user, policy);
        });
    }

    /** UC2: DRAFT to SUBMITTED. */
    public long submit(ObjectId id, long expectedVersion, String user) {
        return gated(() -> {
            rejectIfInvalid(id, user, List.of());
            return writer.update(id, expectedVersion, Set.of(DRAFT),
                    Updates.set(PaymentOrderSchema.STATUS, SUBMITTED.name()), user);
        });
    }

    /** UC2: DRAFT or SUBMITTED to CANCELLED. */
    public long cancel(ObjectId id, long expectedVersion, String user) {
        return gated(() -> {
            rejectIfInvalid(id, user, List.of());
            return writer.update(id, expectedVersion, Set.of(DRAFT, SUBMITTED),
                    Updates.set(PaymentOrderSchema.STATUS, CANCELLED.name()), user);
        });
    }

    /** Runs a call inside the shutdown gate, if this service has one. */
    private <T> T gated(Supplier<T> call) {
        if (gate == null) {
            return call.get();
        }
        gate.enter();
        try {
            return call.get();
        } finally {
            gate.exit();
        }
    }

    /** Throws with all problems at the same time: first the acting user, then the errors of the input. */
    private static void rejectIfInvalid(ObjectId id, String user, List<OrderValidationException.FieldError> errors) {
        List<OrderValidationException.FieldError> all = new ArrayList<>(PaymentOrderChecks.checkUser(user));
        all.addAll(errors);
        if (!all.isEmpty()) {
            throw new OrderValidationException(id, all);
        }
    }

    private static void putIfPresent(Document doc, String key, Object value) {
        if (value != null) {
            doc.append(key, value);
        }
    }

    private static Object toBson(Object value) {
        return switch (value) {
            case BigDecimal d -> new Decimal128(d);
            case NewPaymentOrder.Debtor d -> subtree("account", d.account(), "name", d.name());
            case NewPaymentOrder.Creditor c -> subtree("name", c.name(), "iban", c.iban(),
                    "address", toBson(c.address()));
            case NewPaymentOrder.Address a -> subtree("street", a.street(), "city", a.city(),
                    "postCode", a.postCode(), "country", a.country());
            case NewPaymentOrder.Remittance r -> subtree("reference", r.reference(), "unstructured", r.unstructured());
            case null, default -> value;
        };
    }

    /** Builds a subdocument from key/value pairs. It does not include null values. */
    private static Document subtree(Object... keyValues) {
        Document doc = new Document();
        for (int i = 0; i < keyValues.length; i += 2) {
            putIfPresent(doc, (String) keyValues[i], keyValues[i + 1]);
        }
        return doc;
    }
}
