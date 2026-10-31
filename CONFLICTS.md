# Conflicts and retries

This document covers two cases: a version conflict, and a write whose outcome is unknown.

- **A `VersionConflictException`** means that a different writer changed the document after you read it.
- **README.md, "Handling a conflict",** lists the four options. The sections below give the details of each option.
- **First, reload the document:** `orders.get(id)` with the payment-order service, or `writer.read(id)` with the
  generic writer. Both read with `ReadConcern.MAJORITY` and return the current version.

## The version over HTTP

These rules apply to each option.

- **Each read returns the version,** as a body field or an `ETag` header.
- **Each write sends it back,** as a body field or an `If-Match` header.
- **A version mismatch returns 409 or 412.**
- **The server does not read the version just before the write.** If it did, the check would have no purpose.

## Show the user

Use this option for a user edit.

- **Return 409 with the current document.** The user examines it and sends the change again.

## Recompute

Use this option for a system job.

- **`updateWithRetry` does it.** It reads the fresh document, calculates the change again, and retries a conflict
  with exponential backoff and full jitter.
- **On `RetriesExhaustedException`, requeue the work.** Do not drop it.

## Merge

Use this option when the other write did not change your fields. Send your change again with the new version, but
only after the check below.

**The check:**

1. **Keep the base values.** These are the values that you read before your change. A browser form has them. The
   server keeps no history, so a reload alone cannot show what you started from.
2. **Compare each field that you changed** with its base value in the reloaded document.
3. **Merge only if all of them still match.** If one differs, show the user instead.

**The merge still goes through the version check.** If a third writer changes the document first, you get a new
conflict.

**Merge traps:**

- **Paths overlap by prefix.** A replaced `creditor` overlaps a changed `creditor.address.city`. Compare by path
  prefix, not by exact name.
- **Some fields change together.** If you change `amount` and the other writer changed `currency`, no field
  overlaps, but the result can be wrong. Treat such fields as one group.
- **Equal values are not always untouched (ABA).** A field that was changed and then changed back passes the check.
  Accept this on purpose, or do not merge.
- **Leave the system fields out.** The version and the last-modified pair always differ after another write.

## Drop

Use this option when your change is no longer necessary. Do nothing.

## The usual error

Never send the old change with the new version without the check:

```java
catch (VersionConflictException e) {
    long current = OptimisticLockingWriter.readVersion(orders.get(id).orElseThrow());
    orders.amend(id, current, sameChangesAsBefore, user);   // WRONG: no check, so it writes over the other change
}
```

This code brings back the lost update that the version check prevents.

## Retry after an unknown outcome

A write that fails with `outcomeUnknown = true` may have happened.

- **Update:** a retry applies at most once.
  - If the first attempt applied, the version moved, and the retry gets a conflict.
  - That conflict can be your own write. Reload and compare before you decide.
- **Insert:**
  - The payment-order service makes a new `_id` for each `create`, so a second `create` inserts a second order.
  - If `create` fails with `outcomeUnknown = true`, look up the order by the exception's `id()` before you create
    it again.
  - With the generic writer, the caller supplies the id, so a repeated insert gets `DuplicateKeyException`.
