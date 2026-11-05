# Onboarding: optimistic locking and who-columns for MongoDB

This guide is for people who want to change this repository.

- You can be new to MongoDB.
- If you only want to use the writer, README.md and PORTING.md are sufficient.
- Read this guide once, from start to end. It takes approximately 20 minutes.
- After that, SPEC.md is the source of truth for behaviour.
- DESIGN.md gives the reasons for the decisions.

---

## 1. What this project is

This is a small Java library for optimistic locking and who-columns in MongoDB.

- **Its worked example** stores payment orders.
- **Job 1:** it stops two people (or two servers) from writing over each other's changes without a warning.
- **Job 2:** it records who created each document, and who changed it last.

It has two packages:

- **`com.example.optimistic`** is the generic optimistic-locking writer. Any collection can use it (PORTING.md).
- **`com.example.paymentorders`** is the payment-order service on top of it.

The facts:

- **Stack:** Java 25, Maven, and the official MongoDB Java driver (sync), version 5.13.0. No Spring, no ODM.
- **One collection:** `paymentOrders`.
- **Many writers:** the design assumes many copies ("pods") of the service that write the same documents.
- **Tests:** a single-node MongoDB 9.0.4 replica set in Docker (Testcontainers on Colima). This is the only place
  where the code has run.

## 2. Optimistic locking

Two users open the same order. Both see version 3. Both make changes and save.

```
alice reads v3                         bob reads v3
bob saves:   "update order where id=X AND version=3; set ...; version = 4"   → matched 1 document, now v4
alice saves: "update order where id=X AND version=3; set ...; version = 4"   → matched 0 documents → CONFLICT
```

- Alice's save matches no document, because the version is not 3 now.
- Her save does not write over Bob's change.
- She gets a **`VersionConflictException`**.
- Her app reloads the order and shows her Bob's change.
- Then she decides.

The rules that follow from this:

- **Each update** filters on the order id and on the version that the caller read last.
- **The same database operation** increments the version. This one operation is atomic, so there is no lock and no
  transaction.
- **The caller supplies `expectedVersion`.** If the code read the document to find it, the check would have no
  purpose.
- **`update` never retries.** On a conflict, the writer throws `VersionConflictException`, and its caller decides what
  to do.
- **For a user edit,** the caller shows the user the new state (README.md, "Handling a conflict").
- **`updateWithRetry` is for system jobs.** It reloads, calculates the change again and tries again.

Why the name *optimistic*:

- It assumes that conflicts are rare.
- It does not lock the document before the change.
- It finds a conflict when the conflict happens.

## 3. The data

Each order is one document:

```json
{
  "_id": ObjectId("..."),
  "status": "DRAFT",
  "debtor":   { "account": "ACC-0001", "name": "Globex GmbH" },
  "creditor": { "name": "Acme Ltd", "iban": "DE89370400440532013000",
                "address": { "street": "1 Main St", "city": "Berlin", "postCode": "10115", "country": "DE" } },
  "amount": Decimal128("1250.00"),
  "currency": "EUR",
  "remittance": { "reference": "INV-4471", "unstructured": "Invoice 4471" },
  "_documentMeta": { "version": NumberLong(1) },
  "_createdDate":      ISODate("2026-11-02T08:00:00.123Z"),
  "_createdBy":        "alice",
  "_lastModifiedDate": ISODate("2026-11-02T08:00:00.123Z"),
  "_lastModifiedBy":   "alice"
}
```

- **Six system fields:** `_id`, `_documentMeta` and the four who-columns.
  - Only the writer sets them.
  - Do not put them in order data or changes.
- **The version:**
  - `_documentMeta.version` is a `long`.
  - It starts at 1.
  - Each successful update increases it by 1.
- **Who-columns:**
  - The insert sets the created pair once. After that, nothing changes it.
  - Each update sets the last-modified pair.
- **Money:**
  - `BigDecimal` in Java, and `Decimal128` in MongoDB.
  - Do not use `double`.
- **Dates:**
  - `Instant` in Java, and a BSON date (UTC, milliseconds) in MongoDB.
  - All time values and calculations are UTC.
  - Do not use `java.util.Date` or strings.
  - The client decodes each BSON date as `Instant`, so `doc.get("_createdDate", Instant.class)` works.
  - The driver drops the sub-millisecond part when it writes.
- **Status:**
  - `DRAFT → SUBMITTED`.
  - `DRAFT` or `SUBMITTED → CANCELLED`.
  - `CANCELLED` is final.

**Who is `_createdBy`?**

- It is the `user` argument of each service method.
- The API layer takes it from the verified JWT.
- A job gives its service-account name.
- It never comes from the request body (DESIGN.md, "Caller identity").

## 4. A tour of the code

The layout:

```
src/main/java/com/example/optimistic/       the generic writer
  OptimisticLockingWriter      insert, update, updateWithRetry, read: locking and who-columns
  WhoColumns                   the four who-column names, and their values on insert and update
  RetryPolicy                  exponential backoff with full jitter for system retries
  MongoErrorTranslator         driver exception → DataAccessException (outcome known / unknown)
  exception/                   DataAccessException and its subclasses
src/main/java/com/example/paymentorders/    the payment-order service
  PaymentOptimisticWriter      facade over the generic writer: ObjectId, status, domain exceptions
  PaymentOrderService          create, amend, updateCreditorAddress, submit, cancel, get, updateWithRetry
  PaymentOrderChecks, PaymentOrderSchema    two validation layers, kept in step by ValidationSyncIT
  PaymentOrderClients, PaymentOrderRuntime  client settings; graceful shutdown
  exception/                   OrderNotFound, InvalidStatus, OrderValidation (extend the generic ones), ShuttingDown
```

The classes, and when you change them:

**`OptimisticLockingWriter`** (generic)

- The one class that writes to MongoDB.
- Job 1: optimistic locking (inserts, versioned updates, the diagnosis read, the system retry loop).
- Job 2: the who-columns.
- It makes its collection read dates as `Instant`.
- You change it rarely. Read it carefully first.

**`WhoColumns`** (generic)

- The writer's second job: the four who-column names, and their values on insert and update.
- You change it when the who-columns change.

**`MongoErrorTranslator`** (generic)

- It changes each driver exception into one of our exceptions, with the "outcome unknown" flag.
- You change it when the driver adds an error type.

**`RetryPolicy`** (generic)

- How often and how long `updateWithRetry` retries after conflicts.
- You change it to tune system jobs.

**`PaymentOrderService`**

- The API: `create`, `amend`, `updateCreditorAddress`, `submit`, `cancel`, `get`, `updateWithRetry`.
- It checks the input, then calls the writer.
- You change it to add or change an operation.

**`PaymentOrderChecks`**

- Input validation before any write.
- It returns every problem at once, as field errors.
- You change it to add or change a business rule.

**`PaymentOptimisticWriter`**

- The facade over the generic writer.
- It makes the `ObjectId`.
- It turns the allowed statuses into the precondition filter.
- It throws the domain exceptions.
- You change it rarely.

**`PaymentOrderSchema`**

- It installs MongoDB's own validator, the safety net behind `PaymentOrderChecks`.
- It creates the collection, or replaces the validator (`collMod`).
- It runs once per release, not from the pods.
- You change it when the data shape changes.

**`PaymentOrderClients`**

- It builds the `MongoClient`: pool size, timeouts, dates as `Instant`.
- One for each pod.
- You change it to tune connections.

**`PaymentOrderRuntime`**

- It owns a pod's client and service.
- It does the graceful shutdown: refuse new calls, drain, close once.
- It registers the JVM shutdown hook.
- You use it to wire the service into an application.

- You use it to write a system job.

**`NewPaymentOrder`** and **`PaymentOrderStatus`**

- `NewPaymentOrder` holds records for the order's business data: `Debtor`, `Creditor`, `Address`, `Remittance`.
- `PaymentOrderStatus` is `DRAFT`, `SUBMITTED` or `CANCELLED`.
- You change them when the data shape changes.

**The `exception` packages**

- What callers can get back (section 6).
- The generic exceptions are in `com.example.optimistic.exception`.
- The payment-order exceptions are in `com.example.paymentorders.exception`.

**The steps of an `amend`:**

```
service.amend(id, expectedVersion, changes, user)
  ├─ PaymentOrderChecks: user present? keys allowed? values valid?    → OrderValidationException (no DB call)
  └─ PaymentOptimisticWriter.update(id, expectedVersion, allowed = {DRAFT}, $set changes, user)
     └─ OptimisticLockingWriter.update(id, expectedVersion, precondition = status in {DRAFT}, ...)
       ├─ rejects changes to system fields                             → IllegalArgumentException
       ├─ updateOne( {_id, version = expected, status in allowed},
       │             {$set lastModified*, $inc version by 1, $set changes} )
       ├─ matched 1 → return expectedVersion + 1                       ✓
       └─ matched 0 → read the order once and explain why:
             not there                   → OrderNotFoundException
             version differs             → VersionConflictException
             status not allowed          → InvalidStatusException
```

## 5. Using it

README.md, "The payment-order example", shows the setup and a create, amend and submit.

- **`amend`** replaces whole top-level fields: `debtor`, `creditor`, `amount`, `currency`, `remittance`.
  - Use the record types, for example `new NewPaymentOrder.Remittance(...)`.
  - Use a `BigDecimal` for `amount`.
- **`updateCreditorAddress`** changes only `creditor.address`.
- **`updateWithRetry`** is for system jobs only.
  - It reads, calls your `recompute` function, and retries on a conflict.
  - A user write never retries.
- **`get(id)`** reads the order with `ReadConcern.MAJORITY`.
  - Use it to reload after a conflict or an "outcome unknown" error.
  - It returns the current version for the next write.
  - Do not send the old change with the new version without the check (CONFLICTS.md, "Merge").

## 6. Errors

ERRORS.md lists every exception that a caller can get, and what to do with it.

- **DESIGN.md section 6** has the classification of each driver failure.
- **The root:** all exceptions extend `DataAccessException` (in `com.example.optimistic.exception`), with two
  exceptions:
  - `MongoSecurityException` goes to the caller unwrapped.
  - Programming errors (`IllegalArgumentException`, `IllegalStateException`) are not wrapped.
- **Domain subclasses:** the three domain exceptions extend generic ones, so a caller can catch either name.
  - `OrderNotFoundException` extends `DocumentNotFoundException`.
  - `InvalidStatusException` extends `PreconditionFailedException`.
  - `OrderValidationException` extends `SchemaValidationException`.
- **Sealed outcomes:** `OptimisticWriteException` is sealed, so a `switch` over its four outcomes needs no `default`.
- **A new outcome** must go into the `permits` list of `OptimisticWriteException`.

## 7. Running things

**Setup (once):** install Docker with Colima, and start it.

```sh
colima start
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

| Command | What it runs | Time |
|---|---|---|
| `mvn verify` | All unit and integration tests against a local MongoDB 9.0 replica set in Docker | Approximately 1 min |
| `mvn verify -Dit.test=OptimisticLockingIT` | One integration test class | Shorter |
| `mvn verify -Pstress` | The load test; it checks that no update is lost (OPERATIONS.md) | 20 s+ |
| `MONGO_URI=... mvn verify -Patlas` | Smoke tests against a real Atlas cluster, in a throwaway database. Not run yet | – |

## 8. The tests, and what each checks

The test IDs (T1, E3, S1, G1, ...) are the same as in the tables in SPEC.md (sections 8 to 10 and 12).

- The tests are in `src/test/java/com/example/paymentorders/` and `src/test/java/com/example/optimistic/`.
- The tests run their workers on virtual threads.
- DESIGN.md section 9 lists test techniques that you can use on other collections: the lost-update oracle, latch
  races, and command counts.

| Class | Checks |
|---|---|
| `OptimisticLockingWriterIT` | The generic writer alone, on a plain `widgets` collection with string ids (G1–G10) |
| `WriterEdgeCasesIT` | Edge cases of the generic writer: bad input, version types, extreme dates (G12–G15) |
| `RetryPolicyTest` | The retry wait limits (G11), unit test |
| `OptimisticLockingIT` | Each operation and rule on one client: create, amend, conflicts, status rules, validator, who-columns, dates |
| `ShutdownIT` | Graceful shutdown: calls in progress finish, later calls are refused, the client closes once (S1–S4) |
| `ConcurrencyIT` | Races between simulated pods, one `MongoClient` each: exactly one winner (T8, T9, T13) and no lost updates (T10, T14) |
| `ErrorMappingIT` | Driver failures become the correct exception. It uses MongoDB **fail points** |
| `ValidationSyncIT` | The Java checks and the MongoDB validator require the same fields |
| `PaymentOrderChecksTest`, `PaymentOrderClientsTest` | Unit tests, no database |
| `StressIT` | The load test (profile `stress` only) |
| `AtlasSmokeIT` | A smoke test against a real cluster (profile `atlas` only). Not run yet |
| `MongoTestSupport` | Shared helpers: the container, `newPod()`, `sampleOrder()`, `VIRTUAL_THREADS` |

**Fail points** (`failCommand`):

- A fail point is a MongoDB test feature.
- It makes the server fail the next N commands in a way that you select: an election, a dropped connection, a
  timeout.
- Fail points work only on the test container.

## 9. Making common changes

**To add a business field**, for example `debtor.email`:

1. Add it to the record in `NewPaymentOrder`.
2. Add it to the matching `toBson` case in `PaymentOrderService`.
3. Add it to `PaymentOrderSchema`.
   - Subtrees are *closed*, so MongoDB rejects unknown fields.
   - If you skip this step, each write fails with an `OrderValidationException` that has an empty error list.
4. If the field is required or has a format, add the check in `PaymentOrderChecks` too.
   - Add a required field to `REQUIRED_PATHS`.
   - `ValidationSyncIT` fails until the Java checks and the schema agree.
5. Update SPEC.md section 5 (and section 7 for a validator rule) first, then add tests.
6. **Deploy in the correct order** (OPERATIONS.md, "Validator deployment").
   - The tests always create a new collection, but production has an existing one with the old validator.
   - A looser rule goes in before the code that needs it.
   - A stricter rule goes in with `MODERATE`, then a backfill through the writer, then `STRICT`.

**To add an operation**, for example "put on hold":

1. Add a service method. It checks the input, then calls `writer.update(id, expectedVersion, allowedStatuses,
   change, user)`.
2. Do not call `updateOne` yourself. All writes go through `PaymentOptimisticWriter`.
3. Add it to SPEC.md section 6, with tests for success, conflict and incorrect status.

**Before you commit:**

- Change SPEC.md first, then the code.
- Add or change a test that has an ID from SPEC.md.
- Make sure that `mvn verify` passes.
- Make sure that SPEC.md (and DESIGN.md for decisions) agrees with your change.
- Write documentation in short, active sentences (approximately ASD-STE100), with each key idea in its own bullet.

## 10. Rules you must not break

Code review rejects changes that break these rules from CLAUDE.md and SPEC.md.

- **All writes go through the writer** (`PaymentOptimisticWriter`, which uses `OptimisticLockingWriter`).
  - This includes migrations, backfills, admin edits and triggers.
  - A write that cannot use the writer (for example `updateMany`) must, in the same update, do `$inc` of the version
    and `$set` of the last-modified pair.
- **Each update filters** on `_id` and on the expected version.
- **Each update does `$inc`** on the version by `1L`.
- **Do not write** `_createdDate` or `_createdBy` after the insert.
- **Use `updateOne`** with targeted operators (`$set`, `$unset`, `$inc`).
- **Do not use** `replaceOne`, transactions, or upserts with a version filter.
- **Do not send a user write again blindly** after a conflict. The writer never retries it. A caller may send it again
  only after the merge check (CONFLICTS.md, "Merge").
- **Do not use a default value for a missing version.** Throw an exception.
- **Money** is `BigDecimal` and `Decimal128`.
- **Dates** are `Instant`, from the injected `InstantSource`.
- **Do not use locks or in-memory state** for correctness, because each pod operates like a separate machine.
- **Pin exact dependency versions.** Look them up. Do not guess.

## 11. Gotchas we learned the hard way

- **Testcontainers 2.x starts a standalone MongoDB** unless you call `withReplicaSet()`.
  - A standalone has no retryable writes, so the tests checked less, and nobody saw it.
  - `MongoTestSupport` now calls `withReplicaSet()`.
- **`timeoutMS` changes retries.**
  - With it (our default, 10 s), the driver retries a failed write until the deadline, not only once.
  - This is good during elections.
  - Some tests must see "the error after one retry", so they use a legacy timeout in their URI, which turns off the
    default.
- **On MongoDB 9.0, a `find` fail point also fires during an `update`.** Do not use one to isolate a read that comes
  after an update.
- **"Outcome unknown" is real.**
  - E7b shows a write that *did* occur, but the caller got an error.
  - Always reload before you make a decision.
- **By default, the driver decodes BSON dates as `java.util.Date`**, which prints in the JVM's time zone.
  - The writer changes this for its collection, whatever client built it.
  - `PaymentOrderClients` does the same for the whole client: a `Document` decodes each BSON date as `Instant`.
  - Dates inside arrays still decode as `Date`. The schema has no arrays of dates.
- **A virtual thread reports `TIMED_WAITING` also while it waits for a socket.**
  - A test that finds a sleep by thread state alone then interrupts a driver call instead.
  - Thus, T27 keeps its worker on a platform thread.

## 12. Glossary

| Term | Meaning |
|---|---|
| **Optimistic locking** | Find conflicting writes with a version check, instead of a lock before the change |
| **Version conflict** | The expected version of your write is old; a different writer wrote first |
| **Replica set** | A group of MongoDB servers with the same data: one **primary** takes writes, **secondaries** copy it. Atlas always uses one |
| **Election / failover** | The primary stops, and a secondary takes over. This takes a few seconds |
| **Write concern `majority`** | A write is complete only when most servers have it, so it stays after a failover. We always use it |
| **Read concern `majority`** | Read only data that most servers have, so that it cannot be rolled back |
| **Retryable writes** | The driver sends a write again after a network failure or an election, without applying it two times |
| **`timeoutMS`** | A total time limit for each database operation |
| **Outcome unknown** | We cannot tell if the write happened. Reload to find out |
| **Fail point** | A MongoDB test feature that makes the server fail commands on purpose |
| **Pod** | One running copy of the service (Kubernetes). The tests simulate a pod with its own `MongoClient` |

## 13. Where to read next

1. **SPEC.md:** what the system must do, and all test IDs.
2. **SPEC.md sections 9 and 10:** error mapping and graceful shutdown.
3. **ERRORS.md:** each exception that a caller can get, and what to do.
4. **DESIGN.md:** the reasons for pool settings, retries, validation layers, error classification and caller
   identity.
5. **OPERATIONS.md:** configuration, monitoring, shutdown, the stress profile, and how to run against Atlas.
6. **PORTING.md:** how to use the generic writer on a different collection, and when the pattern does not fit.
7. **`OptimisticLockingWriter.java`:** the class that does all writes.
   - It has approximately 290 lines, and half of them are Javadoc.
   - Read all of it.

**Not done yet:** an idempotency key for `create`.

- It is deferred.
- CONFLICTS.md, "Retry after an unknown outcome", explains why inserts are not safe to retry.
