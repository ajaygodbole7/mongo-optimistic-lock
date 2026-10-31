package com.example.paymentorders;

import static com.example.paymentorders.MongoTestSupport.collection;
import static com.example.paymentorders.MongoTestSupport.sampleOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.paymentorders.exception.OrderValidationException;
import com.mongodb.client.MongoClient;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Keeps the two validation layers the same: the service's checks ({@link PaymentOrderChecks}) and the MongoDB
 * validator ({@link PaymentOrderSchema}). If one layer adds or removes a required field and the other does not, this
 * test fails.
 */
class ValidationSyncIT {

    /** Required in the schema, but the service or the writer sets them. The caller does not set them. */
    private static final Set<String> SET_BY_US = Set.of(
            "status", "_documentMeta", "_documentMeta.version",
            "_createdDate", "_createdBy", "_lastModifiedDate", "_lastModifiedBy");

    private static MongoClient client;
    private PaymentOrderService service;

    @BeforeAll
    static void connect() {
        client = MongoTestSupport.newPod();
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @BeforeEach
    void reset() {
        MongoTestSupport.resetCollection(client);
        service = new PaymentOrderService(MongoTestSupport.database(client));
    }

    @Test
    void t24_serviceAndSchemaRequireTheSameFields() {
        Set<String> schemaRequired = new HashSet<>();
        collectRequired("", PaymentOrderSchema.jsonSchema(), schemaRequired);
        schemaRequired.removeAll(SET_BY_US);

        assertThat(PaymentOrderChecks.REQUIRED_PATHS).isEqualTo(schemaRequired);
    }

    @Test
    void t24_bothLayersRejectEachMissingRequiredField() {
        PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(client));
        for (String path : PaymentOrderChecks.REQUIRED_PATHS) {
            NewPaymentOrder bad = without(path);

            assertThatThrownBy(() -> service.create(bad, "alice")).as("service, %s", path)
                    .isInstanceOfSatisfying(OrderValidationException.class, e -> assertThat(e.errors())
                            .extracting(OrderValidationException.FieldError::path).contains(path));

            // Bypass the service's checks: the schema must reject the same order.
            assertThatThrownBy(() -> writer.insert(PaymentOrderService.toFields(bad), "alice")).as("schema, %s", path)
                    .isInstanceOfSatisfying(OrderValidationException.class, e -> {
                        OptimisticLockingIT.assertRejectedByServer(e);
                        assertThat(e.id()).as("no id for an order that was never stored").isNull();
                    });
        }
        assertThat(collection(client).countDocuments()).isZero();
    }

    /** T24b: for each field-format rule, both the service and the schema reject the order. */
    @Test
    void t24b_bothLayersRejectEachFormatRule() {
        PaymentOptimisticWriter writer = new PaymentOptimisticWriter(collection(client));
        NewPaymentOrder o = sampleOrder();
        NewPaymentOrder.Creditor c = o.creditor();
        NewPaymentOrder.Address a = c.address();
        String longText = "x".repeat(PaymentOrderSchema.MAX_TEXT_LENGTH + 1);
        Map<String, NewPaymentOrder> badByPath = new LinkedHashMap<>();
        badByPath.put("currency", new NewPaymentOrder(o.debtor(), c, o.amount(), "", o.remittance()));
        badByPath.put("currency ", new NewPaymentOrder(o.debtor(), c, o.amount(), "eur", o.remittance()));
        badByPath.put("creditor.iban", withCreditor(o, new NewPaymentOrder.Creditor(c.name(), "DE89", a)));
        badByPath.put("creditor.iban ", withCreditor(o, new NewPaymentOrder.Creditor(c.name(), "", a)));
        badByPath.put("creditor.iban  ", withCreditor(o, new NewPaymentOrder.Creditor(c.name(), c.iban() + "\n", a)));
        badByPath.put("currency  ", new NewPaymentOrder(o.debtor(), c, o.amount(), "EUR\n", o.remittance()));
        badByPath.put("creditor.name", withCreditor(o, new NewPaymentOrder.Creditor(longText, c.iban(), a)));
        badByPath.put("creditor.address.country", withCreditor(o, new NewPaymentOrder.Creditor(c.name(), c.iban(),
                new NewPaymentOrder.Address(a.street(), a.city(), a.postCode(), ""))));
        badByPath.put("debtor.account", new NewPaymentOrder(new NewPaymentOrder.Debtor("", "x"), c, o.amount(),
                o.currency(), o.remittance()));
        badByPath.put("debtor.name", new NewPaymentOrder(new NewPaymentOrder.Debtor("ACC-1", longText), c,
                o.amount(), o.currency(), o.remittance()));
        badByPath.put("remittance.unstructured", new NewPaymentOrder(o.debtor(), c, o.amount(), o.currency(),
                new NewPaymentOrder.Remittance("INV-1", longText)));
        badByPath.put("remittance.reference", new NewPaymentOrder(o.debtor(), c, o.amount(), o.currency(),
                new NewPaymentOrder.Remittance("", "x")));

        badByPath.forEach((key, bad) -> {
            String path = key.trim();
            assertThatThrownBy(() -> service.create(bad, "alice")).as("service, %s", key)
                    .isInstanceOfSatisfying(OrderValidationException.class, e -> assertThat(e.errors())
                            .extracting(OrderValidationException.FieldError::path).contains(path));
            assertThatThrownBy(() -> writer.insert(PaymentOrderService.toFields(bad), "alice")).as("schema, %s", key)
                    .isInstanceOfSatisfying(OrderValidationException.class, OptimisticLockingIT::assertRejectedByServer);
        });
        assertThat(collection(client).countDocuments()).isZero();
    }

    @Test
    void t24_blankUserIsRejectedByTheServiceBeforeTheDatabase() {
        assertThatThrownBy(() -> service.create(sampleOrder(), " "))
                .isInstanceOfSatisfying(OrderValidationException.class, e -> {
                    assertThat(e.errors()).extracting(Object::toString).containsExactly("user: required");
                    assertThat(e.getCause()).isNull();
                });
        assertThat(collection(client).countDocuments()).isZero();
    }

    private static void collectRequired(String prefix, Document schema, Set<String> out) {
        List<String> required = schema.getList("required", String.class, List.of());
        required.forEach(field -> out.add(prefix + field));
        Document properties = schema.get("properties", Document.class);
        if (properties != null) {
            properties.forEach((field, sub) -> {
                if (sub instanceof Document subSchema) {
                    collectRequired(prefix + field + ".", subSchema, out);
                }
            });
        }
    }

    /** The sample order with one required field set to null. */
    private static NewPaymentOrder without(String path) {
        NewPaymentOrder o = sampleOrder();
        NewPaymentOrder.Debtor d = o.debtor();
        NewPaymentOrder.Creditor c = o.creditor();
        NewPaymentOrder.Address a = c.address();
        return switch (path) {
            case "debtor" -> new NewPaymentOrder(null, c, o.amount(), o.currency(), o.remittance());
            case "debtor.account" -> new NewPaymentOrder(
                    new NewPaymentOrder.Debtor(null, d.name()), c, o.amount(), o.currency(), o.remittance());
            case "creditor" -> new NewPaymentOrder(d, null, o.amount(), o.currency(), o.remittance());
            case "creditor.name" -> withCreditor(o, new NewPaymentOrder.Creditor(null, c.iban(), a));
            case "creditor.iban" -> withCreditor(o, new NewPaymentOrder.Creditor(c.name(), null, a));
            case "creditor.address" -> withCreditor(o, new NewPaymentOrder.Creditor(c.name(), c.iban(), null));
            case "creditor.address.country" -> withCreditor(o, new NewPaymentOrder.Creditor(c.name(), c.iban(),
                    new NewPaymentOrder.Address(a.street(), a.city(), a.postCode(), null)));
            case "amount" -> new NewPaymentOrder(d, c, null, o.currency(), o.remittance());
            case "currency" -> new NewPaymentOrder(d, c, o.amount(), null, o.remittance());
            default -> throw new IllegalArgumentException("No test variant for required path " + path);
        };
    }

    private static NewPaymentOrder withCreditor(NewPaymentOrder o, NewPaymentOrder.Creditor creditor) {
        return new NewPaymentOrder(o.debtor(), creditor, o.amount(), o.currency(), o.remittance());
    }
}
