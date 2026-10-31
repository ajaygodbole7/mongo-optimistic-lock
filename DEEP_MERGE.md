# Deep merge: `PatchUpdates.toUpdate`

This is a package-private helper. It changes a nested patch into targeted update operators. The result goes
through `PaymentOptimisticWriter.update(...)`, the same as all other changes. Thus, the same atomic, version-checked
`updateOne` applies it. The helper does not do its own locking.

```java
static Bson toUpdate(Map<String, ?> patch, Set<String> allowedPaths, NullMode nulls)

enum NullMode { REJECT, UNSET, SET_NULL }
```

`amend` does not use this helper, and this is intentional. User edits go through `amend` (top-level fields, whole
subtrees) and `updateCreditorAddress`. The helper is for system jobs, for example a `recompute` function that you
give to `updateWithRetry`. The first caller is the test helper `MongoTestSupport.incrementAmount` (T14, T15).

## What it does

The helper changes nested maps into dotted `$set` paths. Thus, only the given leaves change:

```
Map.of("creditor", Map.of("address", Map.of("city", "Munich")), "amount", new BigDecimal("99.95"))
  -> {"$set": {"creditor.address.city": "Munich", "amount": Decimal128("99.95")}}
```

- **Maps always merge.** The helper does not store a map value as a whole subtree. To replace a whole subtree, use
  `amend`.
- **Values are stored as given**, with the default codecs of the driver: `BigDecimal` becomes `Decimal128`, and
  `Instant` becomes a BSON date. A Java `double` stays a BSON double: use `BigDecimal` for money.
- **No path collisions:** keys do not contain dots, and each key is a map or a leaf. Thus, a `$set` or `$unset`
  path cannot overlap a different path.

## Why allowed paths

The caller supplies the patch. Without a check, each key in the patch becomes a `$set` path. This includes keys
that nobody must write:

| Patch | Becomes | Validator catches it? |
|---|---|---|
| `{"_createdBy": "mallory"}` | a change to a system field | No, it's still a non-empty string. The writer rejects it (`IllegalArgumentException`); the helper rejects it earlier, with the path. |
| `{"status": "SUBMITTED"}` | skips the status checks in `submit` and `cancel` | No, `SUBMITTED` is a valid enum value. |
| `{"_documentMeta": {"version": 1}}` | a change to the version | The writer rejects it (`IllegalArgumentException`) before the round trip; the helper rejects it earlier, with the path. |
| `{"remitance": {...}}` (typo at the root) | stores a stray field | No, the root is open to extra fields. |
| `{"creditor": {"adress": {...}}}` (typo in a subtree) | — | Yes (code 121), because subtrees are closed. |
| `{"creditor.name": "X"}` or `{"$x": 1}` | path collisions or invalid field names | No, or a raw error. |

The validator catches only some of these cases. The allowed paths are a whitelist. The helper checks it before
each write. Thus, a bad patch fails immediately with `IllegalArgumentException`, and it does not get to MongoDB.

## Rules

- **`allowedPaths`** is a set of dotted paths. Each entry allows that path and each leaf below it:
  - `"creditor.address"` allows `creditor.address.city`, `creditor.address.street`, and the other address leaves;
  - `"amount"` allows only `amount`.

  The match is for each path segment. Thus, `"amount"` does not allow `amountX`.
- **The helper always rejects these items, for all allow-lists:**
  - an empty patch or an empty subtree;
  - dotted keys (`"creditor.name"`), `$`-prefixed keys, and empty keys;
  - the system fields of the writer (`_id`, `_documentMeta`, and the four who-columns), also when `allowedPaths`
    lists them. Thus, an incorrect allow-list cannot cancel the rule "Never write the created pair".
- **`null` handling (`NullMode`):**

  | Mode | A `null` value becomes | Notes |
  |---|---|---|
  | `REJECT` | `IllegalArgumentException` | Safest default; absent vs null is never ambiguous. |
  | `UNSET` | `$unset` of that path | As in JSON Merge Patch (RFC 7396). Unsetting a required field fails at the validator (code 121), with the document unchanged. |
  | `SET_NULL` | `$set` the path to BSON null | The validator types every field, so today this always fails with code 121. It is useful only for fields the validator leaves untyped. |

- **Status and version:** the caller sets them through `writer.update(id, expectedVersion, allowed, …)`. The helper
  builds only the change.

## Tests

| ID | Test | Check |
|---|---|---|
| T21 | `PatchUpdatesTest` (unit, no container) | flattening; allow-list prefix semantics; every rejected key type, including allow-listed system fields; all three null modes; `$set` and `$unset` never overlap |
| T22 | `OptimisticLockingIT#t22_patchThroughWriterChangesOnlyGivenLeaves` | through `writer.update`: only the given leaves change, `UNSET` removes an optional leaf, and `SET_NULL` on a string leaf or `UNSET` of a required leaf fails with code 121, leaving the document unchanged |
