package com.example.paymentorders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.paymentorders.exception.OrderValidationException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentOrderChecksTest {

    @Test
    void t23_validOrderHasNoErrors() {
        assertThat(PaymentOrderChecks.checkNew(MongoTestSupport.sampleOrder())).isEmpty();
    }

    @Test
    void t23_reportsEveryProblemAtOnce() {
        var order = new NewPaymentOrder(
                new NewPaymentOrder.Debtor(null, "Globex GmbH"),
                new NewPaymentOrder.Creditor("Acme Ltd", null,
                        new NewPaymentOrder.Address("1 Main St", "Berlin", "10115", null)),
                null, "EUR", null);

        assertThat(PaymentOrderChecks.checkNew(order)).extracting(Object::toString).containsExactly(
                "debtor.account: required", "creditor.iban: required", "creditor.address.country: required",
                "amount: required");
    }

    @Test
    void t23_missingSubtreeReportsOnlyTheSubtree() {
        var order = new NewPaymentOrder(null, null, BigDecimal.ONE, "EUR", null);

        assertThat(PaymentOrderChecks.checkNew(order)).extracting(Object::toString)
                .containsExactly("debtor: required", "creditor: required");
    }

    @Test
    void t23_amendValuesAreTypeAndContentChecked() {
        Map<String, Object> changes = new HashMap<>();
        changes.put("amount", "99.95");
        changes.put("remittance", null);
        changes.put("creditor", new NewPaymentOrder.Creditor("Acme Ltd", "DE89370400440532013000", null));

        assertThat(PaymentOrderChecks.checkChanges(changes)).extracting(Object::toString)
                .containsExactlyInAnyOrder("amount: must be a BigDecimal", "remittance: must not be null",
                        "creditor.address: required");
        assertThatThrownBy(() -> PaymentOrderChecks.checkChanges(Map.of("status", "SUBMITTED")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void t23_amountMustFitDecimal128() {
        var o = MongoTestSupport.sampleOrder();
        var tooPrecise = new NewPaymentOrder(o.debtor(), o.creditor(),
                new BigDecimal("1." + "1".repeat(40)), o.currency(), o.remittance());

        assertThat(PaymentOrderChecks.checkNew(tooPrecise)).extracting(OrderValidationException.FieldError::path)
                .containsExactly("amount");
        assertThat(PaymentOrderChecks.checkChanges(Map.of("amount", new BigDecimal("9".repeat(35)))))
                .extracting(OrderValidationException.FieldError::path).containsExactly("amount");
    }

    @Test
    void t23b_amountMustBeMoreThanZero() {
        var o = MongoTestSupport.sampleOrder();
        for (String amount : new String[] {"0", "0.00", "-1.00"}) {
            var order = new NewPaymentOrder(o.debtor(), o.creditor(), new BigDecimal(amount), o.currency(),
                    o.remittance());
            assertThat(PaymentOrderChecks.checkNew(order)).as(amount).extracting(Object::toString)
                    .containsExactly("amount: must be more than 0");
        }
        assertThat(PaymentOrderChecks.checkChanges(Map.of("amount", new BigDecimal("-5"))))
                .extracting(Object::toString).containsExactly("amount: must be more than 0");
    }

    @Test
    void t23_userIsRequired() {
        assertThat(PaymentOrderChecks.checkUser("alice")).isEmpty();
        assertThat(PaymentOrderChecks.checkUser(" ")).extracting(Object::toString).containsExactly("user: required");
        assertThat(PaymentOrderChecks.checkUser(null)).extracting(Object::toString).containsExactly("user: required");
    }

    @Test
    void t23_addressChecks() {
        assertThat(PaymentOrderChecks.checkAddress(null)).extracting(Object::toString)
                .containsExactly("creditor.address: required");
        assertThat(PaymentOrderChecks.checkAddress(new NewPaymentOrder.Address(null, null, null, "DE"))).isEmpty();
    }
}
