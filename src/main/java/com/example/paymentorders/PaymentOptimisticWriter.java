package com.example.paymentorders;

import static com.mongodb.client.model.Filters.in;

import com.example.optimistic.OptimisticLockingWriter;
import com.example.optimistic.RetryPolicy;
import com.example.optimistic.exception.DocumentNotFoundException;
import com.example.optimistic.exception.PreconditionFailedException;
import com.example.optimistic.exception.RetriesExhaustedException;
import com.example.optimistic.exception.SchemaValidationException;
import com.example.optimistic.exception.VersionConflictException;
import com.example.paymentorders.exception.InvalidStatusException;
import com.example.paymentorders.exception.OrderNotFoundException;
import com.example.paymentorders.exception.OrderValidationException;
import com.mongodb.client.MongoCollection;
import java.time.InstantSource;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

/**
 * The facade over {@link OptimisticLockingWriter} for {@code paymentOrders}. All writes to the collection go through
 * it. It is also the example for other collections: it adds only what is specific to payment orders.
 * <ul>
 *   <li>It makes each new {@code _id} (an {@link ObjectId}).</li>
 *   <li>The allowed statuses are the precondition.</li>
 *   <li>It catches three generic exceptions and throws the payment-order exceptions: {@link OrderNotFoundException},
 *       {@link InvalidStatusException} and {@link OrderValidationException}. All other exceptions pass through.</li>
 * </ul>
 */
public final class PaymentOptimisticWriter {

    private final OptimisticLockingWriter<ObjectId> writer;

    public PaymentOptimisticWriter(MongoCollection<Document> collection) {
        this(collection, InstantSource.system());
    }

    /** {@code clock} supplies the who-column timestamps. Tests give a fixed one. */
    public PaymentOptimisticWriter(MongoCollection<Document> collection, InstantSource clock) {
        this.writer = new OptimisticLockingWriter<>(collection, clock);
    }

    /**
     * Inserts {@code fields} (the business fields and the status) with a new {@code _id}, version 1 and all four
     * who-columns. If the outcome is unknown, the {@code TransientDataAccessException} contains the id. The caller
     * can then find the order, and does not create it again. A validator rejection contains no id.
     */
    public ObjectId insert(Document fields, String user) {
        ObjectId id = new ObjectId();
        try {
            writer.insert(id, fields, user);
        } catch (SchemaValidationException e) {
            throw new OrderValidationException(null, e.reason(), e.getCause()); // nothing was stored: no id
        }
        return id;
    }

    /**
     * Applies {@code changes} if the order is at {@code expectedVersion} and has one of the statuses in
     * {@code allowed}.
     *
     * @return the new version, {@code expectedVersion + 1}
     * @throws OrderNotFoundException, VersionConflictException, InvalidStatusException if no document matches
     */
    public long update(ObjectId id, long expectedVersion, Set<PaymentOrderStatus> allowed, Bson changes, String user) {
        return mapped(id, allowed, () -> writer.update(id, expectedVersion, statusIn(allowed), changes, user));
    }

    /**
     * System read-modify-write. Never use this for user writes.
     *
     * @throws OrderNotFoundException, InvalidStatusException before {@code recompute}
     * @throws RetriesExhaustedException when all {@code policy.maxAttempts()} attempts conflict; requeue the work
     */
    public long updateWithRetry(ObjectId id, Set<PaymentOrderStatus> allowed, Function<Document, Bson> recompute,
                                String user, RetryPolicy policy) {
        return mapped(id, allowed, () -> writer.updateWithRetry(id, statusIn(allowed), recompute, user, policy));
    }

    /** Reads an order with {@code ReadConcern.MAJORITY} from the primary, or null if it does not exist. */
    public Document read(ObjectId id) {
        return writer.read(id);
    }

    private static Bson statusIn(Set<PaymentOrderStatus> allowed) {
        return in(PaymentOrderSchema.STATUS, allowed.stream().map(Enum::name).toList());
    }

    /** Runs a write, and changes the three generic exceptions to the payment-order exceptions, with the cause. */
    private static long mapped(ObjectId id, Set<PaymentOrderStatus> allowed, LongSupplier write) {
        try {
            return write.getAsLong();
        } catch (DocumentNotFoundException e) {
            throw new OrderNotFoundException(id, e);
        } catch (PreconditionFailedException e) {
            String status = e.current().getString(PaymentOrderSchema.STATUS);
            throw new InvalidStatusException(id, e.current(), status, allowed, e);
        } catch (SchemaValidationException e) {
            // The driver exception, not the generic one: OrderValidationException promises it as the cause.
            throw new OrderValidationException(id, e.reason(), e.getCause());
        }
    }
}
