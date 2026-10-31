package com.example.paymentorders;

import static com.example.optimistic.OptimisticLockingWriter.DOCUMENT_META;
import static com.example.optimistic.OptimisticLockingWriter.VERSION_FIELD;
import static com.example.optimistic.WhoColumns.CREATED_BY;
import static com.example.optimistic.WhoColumns.CREATED_DATE;
import static com.example.optimistic.WhoColumns.LAST_MODIFIED_BY;
import static com.example.optimistic.WhoColumns.LAST_MODIFIED_DATE;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bson.Document;

/** Creates {@code paymentOrders}, or updates its validator, with the $jsonSchema validator from SPEC section 7. */
public final class PaymentOrderSchema {

    public static final String COLLECTION = "paymentOrders";

    /** The status field. The facade's precondition and the service's transitions use it. */
    static final String STATUS = "status";

    /** {@code currency}: 3 capital letters. */
    static final String CURRENCY_PATTERN = "^[A-Z]{3}\\z";
    /** {@code creditor.iban}: 2 letters, 2 digits, then 11 to 30 letters or digits (15 to 34 characters). */
    static final String IBAN_PATTERN = "^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}\\z";
    /** The maximum length of names and remittance texts. It also limits the size of the document. */
    static final int MAX_TEXT_LENGTH = 140;

    private PaymentOrderSchema() {
    }

    /**
     * Installs the current validator with {@code strict} and {@code error}. If the collection does not exist, it
     * creates it. If it exists, it replaces the validator with {@code collMod}. Run it once per release, as a
     * deployment step with its own admin credential (the {@code collMod} privilege, for example the {@code dbAdmin}
     * role). The service pods do not run it, and their user needs only {@code readWrite} (SPEC.md section 7).
     */
    public static void apply(MongoDatabase db) {
        apply(db, ValidationLevel.STRICT, ValidationAction.ERROR);
    }

    /**
     * Installs the current validator with the given level and action. For a stricter rule on a live collection, apply
     * it first with {@link ValidationLevel#MODERATE} (updates of old documents that do not match are not checked),
     * backfill the old documents, then apply it with {@link ValidationLevel#STRICT}. SPEC section 7 gives the order.
     */
    public static void apply(MongoDatabase db, ValidationLevel level, ValidationAction action) {
        Document validator = new Document("$jsonSchema", jsonSchema());
        boolean exists = db.listCollectionNames().into(new ArrayList<>()).contains(COLLECTION);
        if (exists) {
            db.runCommand(new Document("collMod", COLLECTION)
                    .append("validator", validator)
                    .append("validationLevel", level.getValue())
                    .append("validationAction", action.getValue()));
        } else {
            db.createCollection(COLLECTION, new CreateCollectionOptions().validationOptions(
                    new ValidationOptions().validator(validator).validationLevel(level).validationAction(action)));
        }
    }

    /** The schema of the validator: the safety net behind the service's own checks ({@link PaymentOrderChecks}). */
    static Document jsonSchema() {
        Document documentMeta = new Document("bsonType", "object")
                .append("required", List.of(VERSION_FIELD))
                .append("additionalProperties", false)
                .append("properties", new Document(VERSION_FIELD,
                        new Document("bsonType", "long").append("minimum", 1L)));

        Document address = closed(List.of("country"), new Document()
                .append("street", string())
                .append("city", string())
                .append("postCode", string())
                .append("country", nonEmpty()));
        Document creditor = closed(List.of("name", "iban", "address"), new Document()
                .append("name", text())
                .append("iban", string().append("pattern", IBAN_PATTERN))
                .append("address", address));

        return new Document("bsonType", "object")
                .append("required", List.of(
                        DOCUMENT_META, CREATED_DATE, CREATED_BY, LAST_MODIFIED_DATE, LAST_MODIFIED_BY,
                        STATUS, "debtor", "creditor", "amount", "currency"))
                .append("properties", new Document()
                        .append(DOCUMENT_META, documentMeta)
                        .append(CREATED_DATE, new Document("bsonType", "date"))
                        .append(CREATED_BY, nonEmpty())
                        .append(LAST_MODIFIED_DATE, new Document("bsonType", "date"))
                        .append(LAST_MODIFIED_BY, nonEmpty())
                        .append(STATUS, new Document("enum",
                                Arrays.stream(PaymentOrderStatus.values()).map(Enum::name).toList()))
                        .append("amount", new Document("bsonType", "decimal")
                                .append("minimum", 0).append("exclusiveMinimum", true))
                        .append("currency", string().append("pattern", CURRENCY_PATTERN))
                        .append("debtor", closed(List.of("account"), new Document()
                                .append("account", nonEmpty())
                                .append("name", text())))
                        .append("creditor", creditor)
                        .append("remittance", closed(List.of(), new Document()
                                .append("reference", text())
                                .append("unstructured", text()))));
    }

    private static Document string() {
        return new Document("bsonType", "string");
    }

    private static Document nonEmpty() {
        return string().append("minLength", 1);
    }

    /** A name or remittance text: not empty, at most {@link #MAX_TEXT_LENGTH} characters. */
    private static Document text() {
        return nonEmpty().append("maxLength", MAX_TEXT_LENGTH);
    }

    /** A closed object schema: no fields other than {@code properties}. */
    private static Document closed(List<String> required, Document properties) {
        Document schema = new Document("bsonType", "object")
                .append("additionalProperties", false)
                .append("properties", properties);
        if (!required.isEmpty()) {
            schema.append("required", required);
        }
        return schema;
    }
}
