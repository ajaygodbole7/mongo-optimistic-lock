# DESIGN: optimistic locking and who-columns for MongoDB

This document gives the design and the reasons for it. SPEC.md gives the requirements and the tests.
OPERATIONS.md gives configuration and how to run things. PORTING.md tells you how to use the generic writer on a
different collection.

## 1. Overview

```
PaymentOrderRuntime                   one per pod: owns the MongoClient, graceful shutdown
  └─ PaymentOrderService              create, amend, updateCreditorAddress, submit, cancel, get, updateWithRetry
       ├─ PaymentOrderChecks          input checks before any write
       └─ PaymentOptimisticWriter     facade: ObjectId ids, status precondition, domain exceptions
            └─ OptimisticLockingWriter   generic: locking + who-columns (WhoColumns; errors: MongoErrorTranslator)
                 └─ MongoClient       from PaymentOrderClients (pool, timeouts)
PaymentOrderSchema                    the $jsonSchema validator (safety net); a deployment step, not the pods
```

**Two jobs.** Every insert and update to the collection goes through one writer; that funnel is what makes both jobs
hold. The generic writer does optimistic locking and the who-columns, with the safety rules that keep both correct (the
six owned field names, the operator allow-list, pinned majority writes and primary reads, the error classification). The
two jobs share one `updateOne`: it increments the version and sets the last-modified pair atomically. `WhoColumns` holds
the second job, so that the writer's code reads as "lock and stamp". The version stays in `_documentMeta`, and the
who-columns stay at the root: a raw `$set` of `_documentMeta` then cannot remove both, and readers and tools get flat
who-columns. The who-columns record the last writer, not a history. The version orders writes; the dates come from the
pod's clock and are for people.

**Generic writer and facade.** The locking mechanism (`com.example.optimistic`) has no payment-order types. Thus, other
collections can use it, and its tests (G1–G19) check it without the payment-order code. The facade
`PaymentOptimisticWriter` adds only three items: it makes the `ObjectId`, it gives the status rule as the precondition
filter, and it changes three generic exceptions to their domain subclasses (`OrderNotFoundException`,
`InvalidStatusException`, `OrderValidationException`). The domain exceptions extend the generic ones. Thus, a caller can
catch either name. The driver-error classification is in its own class, `MongoErrorTranslator`, so that the writer shows
only the pattern.

`OptimisticLockingWriter` is safe for concurrent use. It has no in-memory state. It does one `updateOne` for each write,
and one read only after a zero match. The version check in that `updateOne` gives correctness. The design adds no
locks.

Deliberately not used: the async driver, batching or `bulkWrite`, `findOneAndUpdate` (it returns the document, but it
does not save a round trip on success), a metrics library, a circuit breaker.

### Key decisions

Keep these decisions when you change the code. Each one has a reason and a test that checks it.

| Decision | Reason | Test |
|---|---|---|
| The version filter and `$inc` of the version are in the same `updateOne` | It is atomic on one document. There is no window between a read and a write, and no lock. It is correct for any number of pods. | T8, T10 |
| The caller supplies `expectedVersion` | If the writer read the version just before the write, the check would have no purpose. | T3 |
| `readVersion` throws when the version is missing | There is no silent default. | T20 |
| The diagnosis read occurs only when no document matches | A successful write costs one round trip. | T29 |
| Only `$set`, `$unset` and `$inc` are allowed | An allow-list fails closed. A key check alone lets `$rename` move a value into a system field. | T25 |
| `tryUpdate` is separate from `update` | The retry loop uses the versioned write without the diagnosis read. `update` stays the one public path for user writes. | T29 |
| The precondition check (the status) comes before `recompute` | `recompute` does not run on a cancelled or missing order. | T15 |
| The caller of the generic writer supplies `_id`; error 11000 is `DuplicateKeyException` | After a "maybe written" insert, the caller can find the document. A repeated insert cannot make a second document. | G1 |
| A `timeoutMS` expiry on a write is always `WriteTimeoutException` | One rule that does not depend on driver internals. | E9, E12 |
| `WriteConcern.MAJORITY` and primary reads are pinned in the writer | Client and URI settings cannot make them weaker. | – |
| `OptimisticWriteException` is sealed (four outcomes); `DataAccessException` is open | A caller maps the no-match outcomes with an exhaustive `switch`. Each domain still adds its own exceptions. | compiler |
| The writer makes its collection decode BSON dates as `Instant` | All time is UTC `Instant`. No reader sees `java.util.Date` and the JVM's time zone, whatever client built the collection. | G1, T16, T17 |
| In `combine`, the writer's own `$set` comes first and the caller's `changes` last | `combine` merges each later `$set` into the first one. Thus, the caller's `changes` object is never modified. | G2 |
| `retryWrites` is always on | A driver retry of an applied write returns the original result. It does not apply the write two times. | E2, E7b |
| `get(id)` reads with `ReadConcern.MAJORITY` | A reload does not see a write that can still roll back. | T26, T10 |
| The validator is a safety net behind the service checks | The sync test makes sure that the two layers cannot become different without a warning. | T24 |
| Business rules go into SPEC.md before the code | For example, `amount > 0` went into SPEC.md sections 5 and 7 first, then into both layers. | T23b, T11e |

## 2. Client and connection pool

`PaymentOrderClients.create(connectionString)` builds one `MongoClient` for each pod. OPERATIONS.md, "Configuration",
lists each default, the reason for it, and when the factory applies `timeoutMS`. A connection-string option
overrides a default, except `retryWrites`, which is always `true`: the `RetriedWriteException` reasoning (section 6)
depends on it. This section records the decisions behind the defaults.

**A deadline for each operation:** without `timeoutMS`, a stalled primary or a slow majority can hold a request thread
forever. Thus, the factory applies a default `timeoutMS` unless the URI sets `timeoutMS` or `socketTimeoutMS`
(OPERATIONS.md, `timeoutMS` row).

**10 s and failovers (decision):** 10 s is longer than a planned stepdown (1–3 s), but shorter than an unplanned
primary failure (approximately 10 s before the election starts, plus the election). In that case, writes end as
`WriteTimeoutException`. A longer `timeoutMS` also needs a longer Kubernetes grace
period. The OPERATIONS.md failover procedure includes an unplanned failure.

With `timeoutMS`, the driver retries a failed write until the deadline, not only once. Thus, a write continues
through a normal election.

**`minPoolSize` 0 (decision):** the minimum applies to each replica-set member, and the secondaries get no traffic
from this service. The cost: after a failover, each pod builds its pool to the new primary again, `maxConnecting` (2)
connections at a time. Test an election before you change either value.

**Capacity:** the primary gets a maximum of `pods × (maxPoolSize + 2)` connections, at approximately 1 MB each.
50 pods means approximately 5,100 connections. Decrease `maxPoolSize` through the URI when the pod count increases.

## 3. The write path

```
update(id, expectedVersion, precondition, changes, user)
  rejectSystemFields(changes)                                   → IllegalArgumentException
  updateOne( {_id, _documentMeta.version: expected, precondition},
             {$set _lastModifiedDate/_lastModifiedBy, $inc _documentMeta.version 1L, changes} )
  matched 1 → return expectedVersion + 1
  matched 0 → read once: missing → DocumentNotFoundException      (OrderNotFoundException)
                         version differs → VersionConflictException
                         otherwise → PreconditionFailedException (InvalidStatusException)
```

For payment orders, the precondition is `status in allowed`. The names in brackets are the domain exceptions that
the facade throws.

- **Insert** sets version `1L` and all four who-columns from one `Instant`. It rejects caller-supplied system
  fields.
- **Pinned concerns:** the writer uses `WriteConcern.MAJORITY` and primary reads on its collection. The client and
  the URI cannot change this. Thus, an acknowledged write stays after a failover, and the diagnosis read is
  current.
- **System fields** (`_id`, `_documentMeta`, the four who-columns) are rejected in insert fields and in update
  changes. The writer sets the last-modified pair and the version itself. Nobody writes the created pair after the
  insert.
- **Allowed operators:** a change can use only `$set`, `$unset` and `$inc`. The writer rejects all other operators.
  An allow-list fails closed. A block-list fails open: `$rename` has its target path as a value, not a key, so a
  key check would let `$rename` move a value into `_createdBy` (T25).
- **Read:** `read(id)` (`PaymentOrderService.get`) reads with `ReadConcern.MAJORITY` from the primary. It never
  returns a write that can still roll back. Callers use it to reload after a conflict or an outcome-unknown error.
- **Time:** all time values are UTC `Instant`s. The writer gets the current time from an injected `InstantSource`
  (default `InstantSource.system()`) and gives the driver the `Instant`. The driver stores epoch milliseconds and
  drops the sub-millisecond part; the code does not truncate (T17). The writer adds a codec to its collection
  (`OptimisticLockingWriter.withInstantDates`, composed with the collection's own codecs), so that a `Document`
  decodes each BSON date as `Instant`, whatever client built the collection (G1). `PaymentOrderClients` uses the same
  codec client-wide (T16). Thus, no code sees `java.util.Date` and its default time zone.
- **A zero match at the expected version** usually means that the precondition failed. Before the writer reports
  that, one read with the precondition filter makes sure that the document does not match it now. If it matches (a
  rollback or a race), or if there is no precondition, the writer reports a conflict and logs a `WARN`, so that a
  system job retries instead of dropping the work. The rule is only a filter: the writer never checks it in Java.
- **ABA:** the version restarts at 1 when a document is deleted and inserted again with the same id. A caller that
  holds version 1 of the old document can then write over the new one. Payment orders are safe: each insert makes a
  new `ObjectId`, and the service has no delete. A collection with natural keys must not reuse ids (PORTING.md).

## 4. System retries

**Future scope.** System jobs are not part of the current scope. `updateWithRetry` and its tests stay as a sketch, with
one known limit (below). Fix the limit before a system job uses it in production.

The writer never retries a user write: a conflict goes back to the caller, which decides (CONFLICTS.md).

System read-modify-write uses
`updateWithRetry(id, allowed, recompute, user, RetryPolicy)`:
- Each attempt reads the current document from the primary with the default read concern. A majority read lags the
  primary by one replication delay, so on a hot order most attempts would start from an old version and conflict.
  If the order does not exist, or its status is not allowed, the method throws immediately, before `recompute`.
- Then it calls `recompute(doc)` to build the change. If `recompute` returns `null`, there is nothing to change. The
  method returns the version that it read, and does not write (T28). It does not confirm the version with a majority
  read: on a hot document the majority read lags, and the lag would count as a conflict.
- Then it does the versioned `updateOne` with the version that it read. A failed attempt does not do the diagnosis
  read, because the next attempt's read gives the same information. Only the last attempt does it (T29).
- **On `VersionConflictException`:** wait for a random time between 0 and `min(maxDelay, baseDelay × 2^(attempt − 1))`
  (full jitter), then retry. The jitter keeps pods apart on a hot order. `RetryPolicy.defaults()` is 8 attempts, 5 ms
  base, 100 ms maximum (the maximum applies from the sixth wait).
- **On any transient failure:** stop. If a write possibly occurred, a new calculation can apply it two times.
- **On an interrupt during the wait:** throw `NotSentException` with the
  `InterruptedException` as the cause. The last attempt conflicted, so nothing applied. This is a cancellation, not
  a business conflict (T27).
- **When no attempts remain:** throw `RetriesExhaustedException`, with the last conflict as its cause. It is an
  `OptimisticWriteException`, not a `VersionConflictException`. Thus, a catch for user conflicts cannot catch it by
  accident. A system job must requeue the work. It must not drop it.
- The retry is for each call. Threads and pods share nothing.
- System jobs call it through `PaymentOrderService.updateWithRetry`: inside the shutdown gate and on the runtime's
  client, so that they need no second pool (T30, S2).
- **Threads:** the code is safe on virtual threads (Java 25: `synchronized` does not pin, `Thread.sleep` unmounts,
  the gate uses a `ReentrantLock`). But `maxPoolSize` and latency limit throughput, not the number of threads. With
  virtual threads, put a semaphore sized to the pool in front of the service. If not, thousands of calls queue for a
  connection, and each one that times out is a `WriteTimeoutException`.

**Known limit: a rollback can reuse a version number.** The read in each attempt uses the default (local) read
concern. Thus, it can return a write that is not yet majority-committed:
1. Pod A writes v4 on the primary. A majority has not confirmed it.
2. A system job reads v4 and calculates its change from it.
3. The primary fails, and v4 is rolled back. Pod B writes a different v4 on the new primary, confirmed by a majority.
4. The job's update for version 4 matches B's v4 and overwrites it. No conflict occurs.

The same window lets a `recompute` that returns `null` report a version that never committed. User writes are not
affected: they reload with `ReadConcern.MAJORITY`. The fix is a majority read in `updateWithRetry`, with more
conflicts on hot documents (SPEC section 2 must change first). The single-node replica set in the tests cannot roll
back, so no test shows this case.

## 5. Data model and validation

**Model:** the order has the subtrees `debtor`, `creditor` (with `address`) and `remittance` (SPEC.md section 5).
`NewPaymentOrder` holds them as nested records.

**Changes:**
- `amend` replaces whole top-level fields (`debtor`, `creditor`, `amount`, `currency`, `remittance`).
- `updateCreditorAddress` sets only `creditor.address`.
- Both use one version-checked `updateOne`. The version protects the whole document. Thus, two writers that change
  different subtrees at the same version still have a conflict, and exactly one wins (T13).
- A general deep merge is not part of `amend`. It is a small update language (dotted paths, null as delete, a path
  allow-list), and it is not about locking.

**Validation has two layers:**
1. **Service checks** (`PaymentOrderChecks`, hand-written, no framework) run before each write of `create`, `amend`
   and `updateCreditorAddress`. They check the acting `user`, required fields, value types for `amend`, and that
   `amount` fits `Decimal128`. They report all problems at once as `FieldError` (path, message) in
   `OrderValidationException`. An API can return them as they are. The database is not touched.
2. **The MongoDB validator** (`PaymentOrderSchema`, strict, error) is the safety net for writes that do not go
   through the service, for example patches through the writer. The writer maps its rejection (code 121) to
   `OrderValidationException`, with no field errors and the driver exception as the cause.

`ValidationSyncIT` (T24) makes sure that both layers require the same fields. It reads the `required` lists of the
schema and compares them with `PaymentOrderChecks.REQUIRED_PATHS`. For each path, both layers must reject an order
without it.

Validator rules for the business fields (SPEC.md section 7 has the rules for system fields):

| Field | Rule |
|---|---|
| `status` | `enum: [DRAFT, SUBMITTED, CANCELLED]` |
| `amount` | `decimal`, more than 0 |
| `currency` | `string`, pattern `^[A-Z]{3}\z` (`\z`, not `$`: the server's `$` also matches before a final newline) |
| `debtor` | `object`, `additionalProperties: false`, required `[account]`; `account` not empty; `name` 1–140 characters |
| `creditor` | `object`, `additionalProperties: false`, required `[name, iban, address]`; `name` 1–140 characters; `iban` pattern `^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}\z` |
| `creditor.address` | `object`, `additionalProperties: false`, required `[country]`; all four fields are `string`; `country` not empty |
| `remittance` | `object`, `additionalProperties: false`, no required fields; both fields 1–140 characters |
| root | required also `[status, debtor, creditor, amount, currency]` |

The root accepts additional fields, so that `_id` and future top-level fields need no validator change. The cost:
each change to a subtree shape needs a validator change.

The 140-character limits also limit the size of the document. Without them, only the 16 MB BSON limit stops a long
text, and each read and retry then moves that text. The service checks use the same pattern and length constants as
the schema (`PaymentOrderSchema.CURRENCY_PATTERN`, `IBAN_PATTERN`, `MAX_TEXT_LENGTH`), and count characters as code
points, as `$jsonSchema` does. `ValidationSyncIT` (T24b) makes sure that both layers reject each format rule.

**Validator changes on a live collection:** `PaymentOrderSchema.apply(db)` creates the collection, or replaces the
validator with `collMod`. It runs once per release, never from the service pods: pods of two releases would replace
each other's validator, and the serving credential would need `collMod`. OPERATIONS.md, "Validator deployment", gives
the procedure and the rollout order. The tests always create a new collection, so T31 tests `apply` on an existing
collection with an old order that does not match the new rules.

No secondary indexes: each operation finds the order by `_id`.

## 6. Error mapping

```
DataAccessException                    (com.example.optimistic.exception)
├── OptimisticWriteException           sealed: an update that matched no document; switch is exhaustive
│   ├── DocumentNotFoundException      └── OrderNotFoundException (domain)
│   ├── VersionConflictException
│   ├── PreconditionFailedException    └── InvalidStatusException (domain)
│   └── RetriesExhaustedException      updateWithRetry used all its attempts (cause: the last conflict)
├── SchemaValidationException          the 121 cause from the server, or a document over the BSON limit
│   └── OrderValidationException       (domain) also the field errors from the service checks
├── DuplicateKeyException              error 11000; not transient
├── TransientDataAccessException       sealed; the caller can retry later
│   ├── NotWrittenException            nothing was written: retry later; the version check still applies
│   │   ├── ReadTimeoutException       timeoutMS expired on a read
│   │   └── NotSentException           the request was not sent
│   └── MaybeWrittenException          the write may have happened: reload first
│       ├── WriteTimeoutException      timeoutMS expired on a write
│       ├── RetriedWriteException      the server rejected the driver's retry
│       ├── WriteNotConfirmedException write-concern error: applied on the primary, not confirmed
│       ├── ResponseLostException      sent, the response did not arrive
│       └── ServerOverloadedException  SystemOverloadedError after the driver's retries
├── NotAuthorisedMongoException        server code 13; not transient
└── ShuttingDownException              (domain) call refused during shutdown; no database access
```

`MongoSecurityException` (authentication failure) is not mapped: the caller gets it as it is. The cost: if it occurs
on the driver's retry after a first attempt that applied (for example, credentials rotated during a failover), the
caller cannot tell that the write may have occurred. Reload before you create or apply the change again.

`RetriesExhaustedException` is not a `VersionConflictException`. A "conflict: let the user decide" handler does not
catch it. A system job catches it by name and requeues the work.

`OptimisticLockingWriter` sends each database call (`insertOne`, `updateOne`, the read in `updateWithRetry`, the
diagnosis read) through `MongoErrorTranslator.call(...)`. Its pattern-matching `switch` expressions try the cases from
the top, so the sequence of the cases is the classification order. Each exception keeps the document id where it is
known, and the driver exception as the cause. Classification for driver 5.13.0, checked in the jar:

| Failure on a write | Maps to | Why |
|---|---|---|
| `MongoWriteException` code 121 | `SchemaValidationException`, reason `REJECTED_BY_VALIDATOR`; the facade throws `OrderValidationException` (id `null` for an insert) | – |
| `BsonMaximumSizeExceededException` (the driver did not send it) | `SchemaValidationException`, reason `TOO_LARGE`: the message says "is larger than the 16 MB BSON document limit", the cause is the driver exception (E14) | – |
| `MongoWriteException` code 10334 (the document after an update would exceed 16 MB) | `SchemaValidationException`, reason `TOO_LARGE`, not transient; the cause is the server's write error (G10) | – |
| `MongoWriteException` with the category `DUPLICATE_KEY` (11000) | `DuplicateKeyException`, not transient (G1) | – |
| `MongoWriteConcernException`, whatever its code (also 13) | `WriteNotConfirmedException` | applied on the primary, not confirmed (E8, E19) |
| `MongoSecurityException`, also as a cause | not mapped: the caller gets it as it is (E17) | – |
| server code 13 (Unauthorized), except a write-concern error | `NotAuthorisedMongoException`, not transient (E18) | – |
| `MongoWriteException` without an error label (a bad request: 14, 40, 2), except 121, 10334, 11000 | `IllegalArgumentException` (programming error), nothing applied (G16) | – |
| a read error without an error label, code 2 or 9 (a bad filter) | `IllegalArgumentException` (programming error); other read errors stay transient (G18) | – |
| `MongoOperationTimeoutException` (`timeoutMS`) | `WriteTimeoutException`; on a read, `ReadTimeoutException` (E21) | the driver can retry several times before the deadline, so an earlier attempt may have applied (E9, E12) |
| `MongoTimeoutException`, `MongoSocketOpenException`, `MongoConnectionPoolClearedException`, closed client (`IllegalStateException`, E13) | `NotSentException` | never sent. On the driver's retry, a failure in server selection or connection checkout keeps the first attempt's error (`SpecRetryPolicy.decideWriteProspectiveFailedResult`, driver 5.13 source). Thus, this type means that the only attempt was not sent |
| label `NoWritesPerformed` | `NotWrittenException` | sent, but the server wrote nothing |
| socket read/write errors and timeouts, `MongoSocketClosedException`, `MongoInterruptedException` | `ResponseLostException` | sent, response lost |
| `MongoNotPrimaryException`, `MongoNodeIsRecoveringException`, label `RetryableWriteError` | `RetriedWriteException` | the driver reports only its retry's error (E3, E11) |
| `CodecConfigurationException` | `IllegalArgumentException` (programming error) | – |
| label `SystemOverloadedError` (the server sheds load; the driver already retried) | `ServerOverloadedException`, no `WARN` (E20) | the driver already retried |
| any other driver exception | `MaybeWrittenException` (no subtype), logged at `WARN` (G19) | unknown, so the safe answer |

- **Reads** (the read in `updateWithRetry` and the diagnosis read) do not write. Thus, they are always a
  `NotWrittenException` (E10): `ReadTimeoutException` (E21), `NotSentException`, or the general type.
- **Why `RetriedWriteException`:** after a failed retry, the driver reports the error of the retry. It drops the
  error of the first attempt, unless the error of the retry has `NoWritesPerformed`
  (`SpecRetryPolicy.decideWriteProspectiveFailedResult`, checked in the 5.13 bytecode). Thus, a server rejection
  can hide a first attempt that occurred. This applies to updates and inserts.
- **Inserts** keep the `_id` on a transient exception, so that the caller can find the document instead of
  creating it again. The facade makes the `ObjectId` before the insert.
- **Programming errors** other than those in the table are not caught. `IllegalArgumentException` (bad keys, bad
  patches, system fields) and `IllegalStateException` (missing version) stay as they are.
- **Logging:** `slf4j-api` (compile scope), a `private static final Logger log` in `OptimisticLockingWriter` (a conflict
  that no read explains) and in `MongoErrorTranslator` (an unclassified driver exception). The driver also logs through
  SLF4J.

## 7. Graceful shutdown

- **`PaymentOrderRuntime`** owns the `MongoClient` and builds the `PaymentOrderService` with an `InFlightGate`.
- **`InFlightGate`:** `enter()` throws `ShuttingDownException` when the gate is closed. If not, it counts the call.
  `exit()` decreases the count and signals. `awaitDrained(Duration)` waits on a `ReentrantLock` and `Condition`.
  The gate is only for draining, not for write correctness.
- **Service:** each public method runs between `gate.enter()` and `gate.exit()`. A service without a gate operates
  without these steps.
- **`shutdown(grace)`:** the first caller wins an `AtomicBoolean.compareAndSet`. It closes the gate, waits for a
  maximum of `grace`, then closes the client in a `finally` block, and releases a `CountDownLatch`. A later caller
  does nothing, but waits on the latch. Thus, if the application calls `shutdown` first, the JVM shutdown hook
  waits too, and the JVM does not stop during the drain (S4).
- **`registerShutdownHook(grace)`** registers a JVM shutdown hook that calls `shutdown(grace)`.
- **A call that still runs after the grace period:** in driver 5.13, `MongoClient.close()` does not stop an
  operation that already uses a connection. The call finishes independently within `timeoutMS` and reports its
  result normally (S3). The alternative was to interrupt the threads of the callers. It was rejected, because it
  interrupts threads that we do not own, for example web-server request threads. Thus, the termination grace period
  of the pod must be more than the `preStop` sleep plus `grace` plus `timeoutMS` (OPERATIONS.md).
- **A call after the client closed** is mapped to `NotSentException` (E13).

## 8. Caller identity

`_createdBy` and `_lastModifiedBy` record who made a change. The identity comes through the `user` argument of
each `PaymentOrderService` method, never from the order data:

```
REST request ──► API layer: verify the JWT, take its subject ──► service.amend(id, version, changes, user = sub)
service job  ──► its own service-account id                 ──► service.cancel(id, version, user = "svc-reconciler")
                                                                  │
                                    create (insert): _createdBy = _lastModifiedBy = user
                                    amend / submit / cancel (update): _lastModifiedBy = user only;
                                    the created pair is never written after insert
```

- **Not from the body:** if the payload could contain `_createdBy`, a client could say that it is a different
  person. The identity must come from the item that the API layer verified (the JWT), or from the credentials of
  the service account. Thus, the writer rejects its system fields in insert fields and in update changes
  (T19, T25).
- **Service accounts** (scheduled jobs, reconcilers, system read-modify-write) give their own fixed identity as
  `user`.
- **Checks:** the service rejects a blank or null `user` (`user: required`). The schema requires that both "by"
  columns are strings that are not empty.

## 9. Testing approach

- **Container:** one shared Testcontainers MongoDB `mongo:9.0.4`. Testcontainers 2.x starts a standalone server
  unless `withReplicaSet()` is called. A standalone has no retryable writes. Thus, `MongoTestSupport` calls
  `withReplicaSet()`, then sets the command to keep `--replSet docker-rs` and add
  `--setParameter enableTestCommands=1`.
- **Pods:** each simulated pod is its own `MongoClient`. Concurrency tests release all workers together and assert on
  each result.
- **Threads:** the tests use virtual threads: `Executors.newVirtualThreadPerTaskExecutor()` for workers, and
  `MongoTestSupport.VIRTUAL_THREADS` for `CompletableFuture` tasks that block on a fail point, so that they stay off the
  common pool. `StressIT` still runs exactly pods × threads workers; its latency figures are not comparable with runs
  on platform threads. Two exceptions stay on platform threads: the JVM shutdown hooks, and the T27 worker (below).
- **Fail points** (`failCommand`): each test limits its fail point to its own `appName`, so that other clients are not
  affected. The fail-point tests (section 9.5 of SPEC.md) are the contract for the
  classification table. If the driver behaves differently, change the table, not the tests.
- **Single retry:** with the default `timeoutMS`, the driver retries until the deadline (E16). Tests that classify
  the error after a single retry use a legacy timeout in their URI, which turns off the default.
- **Mongo 9.0 quirk:** a `failCommand` on `find` also fires during an `update`. Thus, E10 uses the read in
  `updateWithRetry`, which occurs before an update.
- **Stress** (`-Pstress`, `StressIT`): pods × threads run user amends and system retries on a set of orders for a
  set time. It asserts the lost-update invariant for each order. Transient failures fail the run, unless
  `-Dstress.allowTransient=true` is set for a run during a failover. Then the invariant becomes a range.
- **Test techniques that you can use on other collections:**
  - **The oracle:** each write sets `amount` to the value that it read + 1. Then the final amount must be the start
    value + the number of successful writes. A lost update loses an increment (T10, T14, `StressIT`).
  - **The control:** T10 also asserts that conflicts occurred. Without the version filter there are no conflicts,
    and T10 fails.
  - **Races:** a latch releases all workers at the same time, and the test asserts on each result (T8, T9, T13).
  - **Count commands, do not measure time:** a `CommandListener` counts the `find` commands, which tests a
    round-trip claim directly (T29).
  - **Force a race with a listener:** a `CommandListener` runs on the calling thread, after the command and before
    the driver returns. G9 uses it to change the document between the update and the diagnosis read. No fail point
    and no sleep is necessary.
  - **Timing of an interrupt:** T27 interrupts during the backoff wait. Its Javadoc explains why a thread that is
    already interrupted gives a different, correct result. Its worker is a platform thread on purpose: a platform
    thread that waits for a socket is `RUNNABLE`, so `TIMED_WAITING` means the sleep. A virtual thread reports
    `TIMED_WAITING` for both.
  - **Check that a test can fail:** remove the line that a test protects, and run the test. G1 (the date codec) and
    G9 (the filtered diagnosis read) were checked this way.
  - **A range under failover:** with transient failures, successful writes ≤ version − 1 ≤ successful +
    outcome-unknown writes (`StressIT`).
- **Limits:** a single-node replica set has no replication delay, no rollback and no real election. The tests check
  the logic, not the behaviour during a real failover. The manual Atlas failover test (OPERATIONS.md) is written for
  that, but it has not been run.
- **Atlas** (`-Patlas`, `AtlasSmokeIT`): uses an external cluster only when `mongo.external=true` and `MONGO_URI` are
  both present. It writes to a database with a unique name and removes it at the end. It uses no fail points. It has
  not been run yet: nothing has been tested on Atlas or AWS EKS.

## 10. Not done

- **Idempotency key for `create`.** Orders come from a UI that saves once and then updates with the version check.
  A repeated Save after a timeout can make a second draft, which can be cancelled before submit. If this changes,
  the small fix is a client-supplied `_id`: MongoDB rejects the duplicate.
- **System jobs (`updateWithRetry`):** future scope. Fix the known limit in section 4 before production use.
- **An `Actor` type for `user`:** `Actor(String id, Kind kind)`, built only by the API layer, so that a caller cannot
  pass a plain string by mistake.
