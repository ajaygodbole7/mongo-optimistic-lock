package com.example.paymentorders;

import com.example.optimistic.OptimisticLockingWriter;
import com.mongodb.client.model.Updates;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.conversions.Bson;

/**
 * Changes a nested patch into targeted update operators (deep merge), for {@link PaymentOptimisticWriter#update}.
 * Nested maps always merge: only the given leaves change. Refer to DEEP_MERGE.md.
 */
final class PatchUpdates {

    /** What a {@code null} value in the patch means. */
    enum NullMode {
        /** Throw {@link IllegalArgumentException}. */
        REJECT,
        /** {@code $unset} the path. */
        UNSET,
        /** {@code $set} the path to BSON null. The validator rejects this for every typed field. */
        SET_NULL
    }

    private PatchUpdates() {
    }

    /**
     * @param allowedPaths dotted paths. Each path allows itself and all paths below it ({@code "creditor.address"}
     *                     allows {@code creditor.address.city})
     * @throws IllegalArgumentException for an empty patch or subtree, a dotted, {@code $}-prefixed or empty key, a
     *                                  system field (even if allowed), a path outside {@code allowedPaths}, or a
     *                                  {@code null} under {@link NullMode#REJECT}
     */
    static Bson toUpdate(Map<String, ?> patch, Set<String> allowedPaths, NullMode nulls) {
        List<Bson> updates = new ArrayList<>();
        flatten("", patch, allowedPaths, nulls, updates);
        return Updates.combine(updates);
    }

    private static void flatten(String prefix, Map<?, ?> patch, Set<String> allowedPaths, NullMode nulls,
                                List<Bson> updates) {
        if (patch.isEmpty()) {
            throw new IllegalArgumentException(prefix.isEmpty() ? "Empty patch" : "Empty subtree at " + prefix);
        }
        for (Map.Entry<?, ?> e : patch.entrySet()) {
            if (!(e.getKey() instanceof String key) || key.isEmpty() || key.contains(".") || key.startsWith("$")) {
                throw new IllegalArgumentException("Invalid field name: " + prefix + e.getKey());
            }
            String path = prefix + key;
            if (prefix.isEmpty() && OptimisticLockingWriter.SYSTEM_FIELDS.contains(key)) {
                throw new IllegalArgumentException("System field not patchable: " + key);
            }
            switch (e.getValue()) {
                case Map<?, ?> nested -> flatten(path + ".", nested, allowedPaths, nulls, updates);
                case null -> updates.add(nullUpdate(allowed(path, allowedPaths), nulls));
                case Object value -> updates.add(Updates.set(allowed(path, allowedPaths), value));
            }
        }
    }

    private static Bson nullUpdate(String path, NullMode nulls) {
        return switch (nulls) {
            case REJECT -> throw new IllegalArgumentException("Null not allowed at " + path);
            case UNSET -> Updates.unset(path);
            case SET_NULL -> Updates.set(path, null);
        };
    }

    /** Returns {@code path} if an allowed path covers it (per segment). If not, it throws. */
    private static String allowed(String path, Set<String> allowedPaths) {
        if (allowedPaths.stream().noneMatch(allowed -> path.equals(allowed) || path.startsWith(allowed + "."))) {
            throw new IllegalArgumentException("Path not allowed: " + path);
        }
        return path;
    }
}
