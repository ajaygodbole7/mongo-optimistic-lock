package com.example.paymentorders.exception;

import com.example.optimistic.exception.PreconditionFailedException;
import com.example.paymentorders.PaymentOrderStatus;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/** The order is at the expected version, but its status does not allow the operation. */
public final class InvalidStatusException extends PreconditionFailedException {

    private final String actualStatus;

    public InvalidStatusException(ObjectId id, Document current, String actualStatus, Set<PaymentOrderStatus> allowed,
                                  Throwable cause) {
        super("Payment order " + id + " has status " + actualStatus + "; allowed " + allowed, id, current, cause);
        this.actualStatus = actualStatus;
    }

    public String actualStatus() {
        return actualStatus;
    }
}
