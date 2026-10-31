package com.example.paymentorders;

import java.math.BigDecimal;

/** The business fields that the caller of {@link PaymentOrderService#create} supplies. Optional fields can be null. */
public record NewPaymentOrder(
        Debtor debtor,
        Creditor creditor,
        BigDecimal amount,
        String currency,
        Remittance remittance) {

    public record Debtor(String account, String name) {
    }

    public record Creditor(String name, String iban, Address address) {
    }

    public record Address(String street, String city, String postCode, String country) {
    }

    public record Remittance(String reference, String unstructured) {
    }
}
