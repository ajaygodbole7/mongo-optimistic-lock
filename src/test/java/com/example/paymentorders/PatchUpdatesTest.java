package com.example.paymentorders;

import static com.example.paymentorders.PatchUpdates.NullMode.REJECT;
import static com.example.paymentorders.PatchUpdates.NullMode.SET_NULL;
import static com.example.paymentorders.PatchUpdates.NullMode.UNSET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoClientSettings;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

class PatchUpdatesTest {

    private static final Set<String> ALLOWED = Set.of("creditor.address", "amount", "remittance");

    private static BsonDocument render(Bson update) {
        return update.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    }

    /** {@code {key: value}} that allows a null value. {@code Map.of} does not allow it. */
    private static Map<String, Object> nullable(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    @Test
    void t21_flattensNestedMapsToDottedSetPaths() {
        BsonDocument update = render(PatchUpdates.toUpdate(Map.of(
                "creditor", Map.of("address", Map.of("city", "Munich", "postCode", "80331")),
                "amount", new BigDecimal("99.95")), ALLOWED, REJECT));

        assertThat(update).isEqualTo(BsonDocument.parse("""
                {"$set": {"creditor.address.city": "Munich", "creditor.address.postCode": "80331",
                          "amount": {"$numberDecimal": "99.95"}}}"""));
    }

    @Test
    void t21_allowedPathCoversItselfAndEverythingBelow() {
        assertThat(render(PatchUpdates.toUpdate(Map.of("remittance", Map.of("unstructured", "x")), ALLOWED, REJECT)))
                .isEqualTo(BsonDocument.parse("{\"$set\": {\"remittance.unstructured\": \"x\"}}"));

        assertThatThrownBy(() -> PatchUpdates.toUpdate(Map.of("creditor", Map.of("name", "X")), ALLOWED, REJECT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("creditor.name");
        assertThatThrownBy(() -> PatchUpdates.toUpdate(Map.of("currency", "USD"), ALLOWED, REJECT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
        assertThatThrownBy(() -> PatchUpdates.toUpdate(Map.of("amountX", 1), ALLOWED, REJECT))
                .as("prefix match is per path segment, not per character")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void t21_rejectsInvalidPatches() {
        Set<String> allowEverything = Set.of("creditor", "amount", "_createdBy", "_documentMeta", "_id");
        List<Map<String, ?>> invalid = List.of(
                Map.of(),                                           // empty patch
                Map.of("creditor", Map.of()),                       // empty subtree
                Map.of("creditor.name", "X"),                       // dotted key
                Map.of("$set", Map.of("amount", 1)),                // operator key
                Map.of("", "X"),                                    // empty key
                Map.of("_createdBy", "mallory"),                    // system field, even though allowed
                Map.of("_documentMeta", Map.of("version", 1L)),
                Map.of("_id", "x"));
        for (Map<String, ?> patch : invalid) {
            assertThatThrownBy(() -> PatchUpdates.toUpdate(patch, allowEverything, UNSET))
                    .as("patch %s", patch)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void t21_nullModes() {
        Map<String, Object> patch = Map.of("creditor", Map.of("address", nullable("street", null)));

        assertThatThrownBy(() -> PatchUpdates.toUpdate(patch, ALLOWED, REJECT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("creditor.address.street");
        assertThat(render(PatchUpdates.toUpdate(patch, ALLOWED, UNSET)))
                .isEqualTo(BsonDocument.parse("{\"$unset\": {\"creditor.address.street\": \"\"}}"));
        assertThat(render(PatchUpdates.toUpdate(patch, ALLOWED, SET_NULL)))
                .isEqualTo(BsonDocument.parse("{\"$set\": {\"creditor.address.street\": null}}"));
    }

    @Test
    void t21_setAndUnsetPathsNeverOverlap() {
        Map<String, Object> address = new HashMap<>(Map.of("city", "Munich"));
        address.put("street", null);

        BsonDocument update = render(PatchUpdates.toUpdate(Map.of("creditor", Map.of("address", address)),
                ALLOWED, UNSET));

        assertThat(update.getDocument("$set").keySet()).containsExactly("creditor.address.city");
        assertThat(update.getDocument("$unset").keySet()).containsExactly("creditor.address.street");
    }
}
