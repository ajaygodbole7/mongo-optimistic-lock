package com.example.paymentorders.exception;

import com.example.optimistic.exception.DocumentNotFoundException;
import org.bson.types.ObjectId;

public final class OrderNotFoundException extends DocumentNotFoundException {

    public OrderNotFoundException(ObjectId id, Throwable cause) {
        super(id, "Payment order " + id + " not found", cause);
    }
}
