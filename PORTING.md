# Porting: optimistic locking on your own collection

This guide tells you how to use the generic `OptimisticLockingWriter` on a collection that is not `paymentOrders`.
The writer does two jobs: optimistic locking and the who-columns, with the safety rules that keep both correct. The
guide also tells you when the pattern does not fit. SPEC.md section 12 is the contract of the generic writer.
`OptimisticLockingWriterIT` (G1–G10) and `WriterEdgeCasesIT` (G12–G19) check it on a plain collection with string ids and no validator.

## 1. The pattern in six rules

1. Every insert and update to the collection goes through one writer. That one funnel gives you both optimistic
   locking and who-column safety. An insert or update that bypasses it breaks both.
2. Each document has a version (a BSON `long`). An insert sets it to 1.
3. The caller reads the document and keeps its version. The writer does not read the version for the caller.
4. Each update is one `updateOne` with the filter `{_id, version: expected}` and the change plus `$inc` of the
   version by 1. The database applies the filter and the change atomically on one document.
5. `matchedCount == 1` means success. `matchedCount == 0` does not always mean "a different writer won". One read
   tells which case applies: the document is missing, the version is different (a conflict), or the precondition is
   false.
6. On a conflict, a user reloads and decides again. A system job reads again, calculates the change again from the
   new state, and tries again. Nobody sends the old change with the new version without a check.

```java
UpdateResult r = collection.updateOne(
        and(eq("_id", id), eq("_documentMeta.version", expected), precondition),
        combine(set("_lastModifiedDate", now), set("_lastModifiedBy", user), inc("_documentMeta.version", 1L),
                changes));   // changes last
if (r.getMatchedCount() == 0) {
    Document current = collection.find(eq("_id", id)).first();   // the diagnosis read
    // null → not found; version != expected → conflict; same version → the precondition failed
}
```

`OptimisticLockingWriter` is this code plus the parts that are easy to get wrong: majority writes, the operator
allow-list, the system-field check, the who-columns, the error classification (not written or maybe written), and the system
retry loop with full jitter.

## 2. Use the generic writer

```java
// One writer per collection, one MongoClient per process.
OptimisticLockingWriter<String> widgets = new OptimisticLockingWriter<>(db.getCollection("widgets"));

widgets.insert("w-1", new Document("name", "first").append("tenant", "t1"), user);    // version 1

long v2 = widgets.update("w-1", 1L, eq("tenant", "t1"), Updates.set("name", "second"), user);

long v3 = widgets.updateWithRetry("w-1", null,                     // null: no precondition
        // calculated again from the fresh read
        doc -> Updates.set("total", doc.getInteger("price", 0) * doc.getInteger("quantity", 0)),
        "svc-pricing", RetryPolicy.defaults());
```

- **`K`** is the type of `_id`. The caller makes the id before the insert. Thus, after a "maybe written" insert,
  the caller can look up the document. A second insert with the same id gives `DuplicateKeyException`, not a
  second document.
- **Field names** are fixed: `_documentMeta.version` (`OptimisticLockingWriter.DOCUMENT_META`, `VERSION_FIELD`) and
  the four who-columns (`WhoColumns.CREATED_DATE` and the others). A collection with other names copies the two
  classes and changes the constants. A collection that does not want who-columns copies them and removes
  `WhoColumns`.
- **The precondition** is an extra filter, or `null`. After a zero match at the expected version, the writer reports
  `PreconditionFailedException` with the current document.
- **A facade per collection** holds the domain rules. `PaymentOptimisticWriter` is the example: it makes the ids,
  gives the status set as the precondition, and catches three generic exceptions to throw payment-order exceptions.
  A different collection writes its own facade, and handles the generic exceptions in its own way.

## 3. Does the pattern fit your collection?

| Collection shape | Fit | What to do |
|---|---|---|
| One document per business object, edited by users (order, profile, case) | Yes | Use the writer as it is. |
| A state machine (draft → submitted → cancelled) | Yes | Put the allowed states in the precondition filter. |
| A hot counter or balance that many writers increment | No | A version makes each increment conflict with each other increment. Keep the counter in a separate collection without a version, and use `$inc` with a guard in the filter (for example `{balance: {$gte: amount}}`). That collection does not use this writer. If the counter must stay in a versioned document, use `updateWithRetry` and accept the conflicts. |
| Append-only data (events, audit logs, time series) | No | Nothing is updated, so nothing can be lost. Insert only. |
| A rule across several documents (a transfer between two accounts) | No | The writer has no `ClientSession` and no transactions. Change the model so that the rule is on one document, or use a transaction or a ledger outside this writer. |
| A large document with parts that different users edit at the same time | Partly | The version protects the whole document. Two edits of different parts at the same version still conflict (T13). Split the parts into separate documents if the conflicts are a problem. |
| A sharded collection | Yes | Put the shard key in the precondition filter, so that each update goes to one shard (G7). |
| Arrays that callers change with `$push`, `$pull` or `arrayFilters` | Partly | The allow-list has only `$set`, `$unset` and `$inc`. To add an operator, change `ALLOWED_OPERATORS` in the writer, with care. The writer does not pass `arrayFilters`. |

## 4. What is specific to payment orders

These parts are not in the generic package. Replace them for your collection:

| Payment-order part | What it does | For your collection |
|---|---|---|
| `PaymentOptimisticWriter` | Facade: `ObjectId` ids, status precondition, domain exceptions | Write your own facade |
| `PaymentOrderService` | Use cases, input checks, shutdown gate | Your own service |
| `PaymentOrderChecks`, `PaymentOrderSchema`, `ValidationSyncIT` | Two validation layers that are kept in step | Your own checks and `$jsonSchema`; copy the sync test |
| `PaymentOrderClients` | Pool, `timeoutMS`, `appName`, JMX listener, BSON dates as `Instant` | Reuse the settings; OPERATIONS.md, "Configuration", gives each value and its reason |
| `PaymentOrderRuntime`, `InFlightGate` | Graceful shutdown | Reuse the pattern |

## 5. Checklist

1. **Choose the id.** Prefer an id that the caller makes before the insert (`ObjectId`, UUID, or a natural key).
   Do not reuse the id of a deleted document (section 6, "ABA").
2. **Add a version to existing documents** before the writer goes live. `readVersion` throws when the version is
   missing. It does not use a default. Example:
   `updateMany({"_documentMeta.version": {$exists: false}}, {$set: {"_documentMeta.version": 1L}})`.
3. **Find every writer of the collection.** This includes migrations, backfills (`updateMany`), edits in Compass or
   mongosh, Atlas triggers and other services. Each one must go through the writer (a migration uses its own user,
   such as `migration-2026-11`). If not, it must, in the same update, do `$inc` of the version and `$set` of the
   last-modified pair. A write that does not change the version is not visible to the version check, and a caller that
   holds the old version writes over it. A write that does not set the last-modified pair leaves them naming the
   previous writer.
4. **Add a `$jsonSchema` validator** that requires the version as a `long` with `minimum: 1`. Roll it out with
   `moderate`, backfill, then `strict` (SPEC.md section 7).
5. **Test each precondition** with a document that matches it and one that does not (G3, G7).
6. **Map the exceptions** to your API: conflict → 409 with the current document; precondition failed → 409 or 422;
   not found → 404; `DuplicateKeyException` → 409; `MaybeWrittenException` → reload first; `NotWrittenException` → retry later.
7. **Decide the retry policy** for system jobs (`RetryPolicy`). A job must requeue on `RetriesExhaustedException`.
8. **Copy the tests:** the lost-update oracle (each write sets a value to the value that it read + 1), a latch
   race with one winner, and the fail-point tests for the error classification (DESIGN.md section 9).

## 6. Caveats

- **Granularity.** One version per document. Edits of different fields at the same version conflict.
- **ABA.** The version restarts at 1 when a document is deleted and inserted again with the same id. A caller that
  holds version 1 of the old document can then write over the new document. Do not reuse ids. Prefer a soft delete
  (a status) to a delete.
- **Deletes.** The writer has no delete. To add one, use `deleteOne({_id, version: expected})`, and on
  `deletedCount == 0` do the same diagnosis read as an update.
- **Upserts.** Do not use an upsert with a version filter. If the version moved, the filter matches nothing, and the
  upsert tries to insert a second document with the same `_id`. MongoDB rejects it with error 11000. Use `insert`
  for a new document, and `update` for an existing one. On `DuplicateKeyException`, read the document and update it.
- **Retries after `MaybeWrittenException`.** A retried update applies at most once: if the first attempt applied, the
  version moved, and the retry gets a conflict. That conflict can be your own write. Reload and compare before you
  decide. A retried insert with the same id gets `DuplicateKeyException`, which also means "look it up".
- **Pinned settings.** The writer always uses `WriteConcern.MAJORITY` and primary reads, and reads BSON dates as
  `Instant` (it adds a codec to the collection's own codecs). Do not remove this: an
  acknowledged write that a failover rolls back is a lost update that no version check sees.
