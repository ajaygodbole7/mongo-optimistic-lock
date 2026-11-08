# SPEC: Optimistic Locking in MongoDB

## 1. Goal

Implement optimistic locking for one MongoDB collection, `paymentOrders`, with the native Java driver. The service operates as many pods. All pods write to the same collection. The tests simulate this locally with Testcontainers. Each simulated pod has one `MongoClient`.

There are two use cases:
- **UC1. Amend a draft order:** the write checks only the version.
- **UC2. Submit or cancel an order:** the write checks the version and a status condition.

The work is complete when `mvn verify` passes with all tests in sections 8, 9, 10 and 12.

The structure, class names, and APIs below are only suggestions. The behavior, data model, and tests are requirements.

## 2. How it works

Each update filters on `_id` and on the version that the caller read. The same atomic write increments the version. If a different writer changed the document first, no document matches. The caller then gets a conflict.

```
pod-1 reads v3        pod-2 reads v3
pod-2: updateOne({_id, version: 3}, {..., $inc version}) -> matched 1, now v4
pod-1: updateOne({_id, version: 3}, {..., $inc version}) -> matched 0 -> conflict
```

The writer does not retry a user write after a conflict. The caller reloads the order and decides. The caller may send the change again only after the merge check in CONFLICTS.md, "Merge".

A system read-modify-write can retry. It uses `PaymentOptimisticWriter.updateWithRetry(id, allowed, recompute, user, RetryPolicy)`:
- Each attempt reads the current document from the primary with the default read concern (a majority read lags the primary and causes conflicts on hot orders). If the order does not exist or its status is not allowed, the method throws immediately. If not, it calculates the change again from the document and writes through the same versioned update.
- If `recompute` returns `null`, there is no change to make. The method returns the version that it read, and does not write.
- A failed attempt does not do the diagnosis read. The next attempt's read gives the same information. Only the last attempt does the diagnosis read.
- Only a version conflict causes a retry, with full-jitter backoff. `RetryPolicy.defaults()` is 8 attempts, a 5 ms base delay and a 100 ms cap (the cap applies from the sixth wait).
- `PaymentOrderService.updateWithRetry` gives system jobs this method through the service, inside the shutdown gate and on the runtime's client. A system job does not need a second `MongoClient`.
- When no attempts remain, the method throws `RetriesExhaustedException`, with the last conflict as its cause. It is an `OptimisticWriteException`, not a `VersionConflictException`. Thus, a `catch (VersionConflictException e)` cannot catch it by accident. A system job must requeue the work. It must not drop it.
- If the thread is interrupted during the wait between attempts, the method throws `NotWrittenException`. The last attempt did not apply.

## 3. Rules

- The who-columns are at the document root: `_createdDate`, `_createdBy`, `_lastModifiedDate`, `_lastModifiedBy`. An insert sets all four. An update sets only the last-modified pair. An update does not touch the created pair.
- The version is at `_documentMeta.version`. It is a `long`. It starts at `1`, and `$inc` increments it by `1L`. `_documentMeta` contains nothing else.
- The caller supplies `expectedVersion`. Do not read the document to get it.
- Use `updateOne` with targeted `$set`/`$inc`. Do not use `replaceOne`, upserts with a version filter, or transactions. (An upsert with a version filter that matches nothing tries to insert a second document with the same `_id`, and fails with error 11000 instead of a conflict. To create a document, use `insert`.)
- A write is successful when `getMatchedCount() == 1`. If the count is zero, read the document one time. Then throw the first error that applies, in this sequence: not found; version conflict; invalid status.
- Read the version with `get("version", Number.class).longValue()`. If the version is missing, throw an exception. Do not use a default value.
- All writes go through one shared component: the generic `OptimisticLockingWriter` (section 12), used through the payment-order facade `PaymentOptimisticWriter`. This component pins `WriteConcern.MAJORITY` and primary reads on its collection, for all client settings. It also rejects changes that touch system fields (`_id`, `_documentMeta`, the who-columns). It accepts only the operators `$set`, `$unset` and `$inc`, and rejects all other operators. An allow-list fails closed. A block-list fails open: for example, `$rename` can move a value into a system field.
- "All writes" includes writes that are not service calls: migrations, backfills, edits in Compass or mongosh, and Atlas triggers. Prefer the writer for these writes too (for example `updateWithRetry` with a migration user such as `migration-2026-11`): it increments the version and sets the last-modified pair. A write that cannot use the writer (for example `updateMany`) must, in the same update, do `$inc` of the version by `1L` and `$set` of `_lastModifiedDate` and `_lastModifiedBy`. It never writes the created pair. A write that does not change the version is not visible to the version check, and a caller that holds the old version writes over it. A write that does not set the last-modified pair leaves them naming the previous writer.
- Money: use `BigDecimal` / `Decimal128`. Dates: use `Instant` in Java. Store dates as a BSON date (UTC milliseconds since the epoch, shown as ISO-8601 `ISODate("…Z")`). Do not store dates as strings. All time values and calculations are UTC. The code never uses `java.util.Date`: the generic writer adds a codec to its collection so that a `Document` decodes each BSON date as `Instant`, whatever client built the collection; `PaymentOrderClients` adds the same codec client-wide. The driver drops the sub-millisecond part when it writes; the code does not truncate.
- `insert` rejects system fields that the caller supplies (`_id`, `_documentMeta`, the four who-columns). The writer sets these fields.
- The service checks its input before it writes (`create`, `amend`, `updateCreditorAddress`). An invalid order causes `OrderValidationException`, with a list of field errors (for example `creditor.address: required`). This occurs before the service touches the database.
- The MongoDB validator stays as a safety net for writes that do not go through the service (for example, patches through the writer). The writer also maps a server rejection (code 121) to `OrderValidationException`. This exception has no field errors, and the driver exception is its cause. A test makes sure that the required fields of the service and of the schema are the same.
- Do not use locks or in-memory state for correctness. Each simulated pod must operate like a separate JVM.

## 4. Stack

Java 25, Maven, `mongodb-driver-sync` 5.13.0, JUnit Jupiter, AssertJ, Testcontainers MongoDB with a pinned `mongo:9.0.x` image. Pin exact versions. The driver and server versions above are fixed. Look up the other versions. Do not guess. The container runtime is Colima.

Each pod has one `MongoClient`. `PaymentOrderClients.create(connectionString)` builds it. The defaults are: `maxPoolSize` 100, `minPoolSize` 0 (the minimum applies to each replica-set member, and secondaries get no traffic), `maxConnecting` 2, `waitQueueTimeoutMS` 5 s, `maxIdleTimeMS` 5 min, `connectTimeoutMS` 10 s, `serverSelectionTimeoutMS` 10 s, and `timeoutMS` 10 s (a deadline for each operation). The factory applies `timeoutMS` unless the URI sets `timeoutMS` or `socketTimeoutMS`. `waitQueueTimeoutMS` alone does not turn it off, because it limits only the wait for a connection, not the operation. With `timeoutMS`, the driver limits the wait for a connection by `timeoutMS` and ignores `waitQueueTimeoutMS`. Thus, when the URI sets `waitQueueTimeoutMS` without `timeoutMS`, the factory logs a `WARN` that says so. Without `timeoutMS` and without `socketTimeoutMS`, an operation has no deadline. When the URI sets `socketTimeoutMS` without `timeoutMS` (legacy timeouts), the factory logs a `WARN`.

The 10 s deadline is longer than a planned primary stepdown (1–3 s), but shorter than an unplanned primary failure (approximately 10 s before an election starts, plus the election). During an unplanned failure, writes end as `WriteTimeoutException`. `minPoolSize` stays 0: after a failover, each pod builds its pool to the new primary again. Both are decisions; OPERATIONS.md tells you how to test them. An option in the connection string overrides the default. The exception is `retryWrites`, which is always on. `appName` defaults to the pod name (the `HOSTNAME` environment variable, else `payment-orders`) unless the URI sets it, so that Atlas, server logs and `currentOp` show which pod opened a connection. The factory registers the driver's `JMXConnectionPoolListener`, so that pool size, checked-out connections and wait queues are visible for monitoring. Metrics libraries stay out of scope.

## 5. Data model

```json
{
  "_id": { "$oid": "..." },
  "status": "DRAFT",
  "debtor": { "account": "ACC-0001", "name": "Globex GmbH" },
  "creditor": {
    "name": "Acme Ltd",
    "iban": "DE89370400440532013000",
    "address": { "street": "1 Main St", "city": "Berlin", "postCode": "10115", "country": "DE" }
  },
  "amount": { "$numberDecimal": "1250.00" },
  "currency": "EUR",
  "remittance": { "reference": "INV-4471", "unstructured": "Invoice 4471" },
  "_documentMeta": { "version": { "$numberLong": "1" } },
  "_createdDate": { "$date": "2026-11-02T08:00:00Z" },
  "_createdBy": "alice",
  "_lastModifiedDate": { "$date": "2026-11-02T08:00:00Z" },
  "_lastModifiedBy": "alice"
}
```

Required: `status`, `debtor.account`, `creditor.name`, `creditor.iban`, `creditor.address.country`, `amount`, `currency`. `amount` must be more than 0.

Field formats (both the service checks and the validator):
- Required strings (`debtor.account`, `creditor.name`, `creditor.iban`, `creditor.address.country`, `currency`) must not be empty.
- `currency` is 3 capital letters (`^[A-Z]{3}\z`). Each pattern ends with `\z` (the end of the text), not `$`: in the server's regex engine, `$` also matches before a final newline.
- `creditor.iban` has the IBAN format: 2 letters, 2 digits, then 11 to 30 letters or digits (`^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}\z`, so 15 to 34 characters). This checks the format, not the checksum.
- `debtor.name`, `creditor.name`, `remittance.reference` and `remittance.unstructured`, when present, must not be empty and have a maximum of 140 characters. This also limits the size of the document. Optional: `debtor.name`, the other address fields, and `remittance` with its two fields.

Status: `DRAFT` → `SUBMITTED`; `DRAFT` or `SUBMITTED` → `CANCELLED`. `CANCELLED` is final.

## 6. Operations

| Operation | Filter (besides `_id` and version) | Change |
|---|---|---|
| `create(fields, user)` | (insert) | status `DRAFT`, version 1 |
| `amend(id, expectedVersion, changes, user)` | `status = DRAFT` | `$set` the given top-level fields (`debtor`, `creditor`, `amount`, `currency`, `remittance`); a subtree value replaces the whole subtree; any other key is rejected |
| `updateCreditorAddress(id, expectedVersion, address, user)` | `status = DRAFT` | `$set` `creditor.address` only |
| `submit(id, expectedVersion, user)` | `status = DRAFT` | `status = SUBMITTED` |
| `cancel(id, expectedVersion, user)` | `status in [DRAFT, SUBMITTED]` | `status = CANCELLED` |
| `get(id)` | (read) | none: returns the order, or nothing if it does not exist |

Each write operation returns the new version (`expectedVersion + 1`). `get` reads with `ReadConcern.MAJORITY` from the primary. Use it to reload after a conflict or after an outcome-unknown error.

## 7. Schema

Create the collection with a `$jsonSchema` validator (strict, error) that requires:
- the four who-columns with the correct types: the dates are `date`, and the "by" columns are `string`s that are not empty;
- `_documentMeta` with only `version` (long, ≥ 1);
- the required fields from section 5; `status` in the enum; `amount` as `decimal` more than 0; `currency` as `string`;
- the field formats from section 5;
- closed subtrees (`additionalProperties: false`) for `debtor`, `creditor`, `creditor.address` and `remittance`, with `string` leaves.

The root accepts additional fields. Do not add secondary indexes. Each operation finds the document by `_id`.

`PaymentOrderSchema.apply(db)` installs the validator. If the collection does not exist, it creates it. If it exists, it replaces the validator with `collMod`. Thus, a validator change reaches an existing collection. The database user needs the `collMod` privilege (for example the `dbAdmin` role); `readWrite` is not sufficient. Run `apply` once per release, as a deployment step (for example a Kubernetes Job, a Helm pre-upgrade hook or a CI step) with its own admin credential. The service pods do not run it: during a rolling update, pods of two releases would replace each other's validator, and the pods' credential would need `collMod`. The pods' user needs only `readWrite`. Rollout order for a schema change on a live collection:
1. To allow a new field or a looser rule: apply the new validator first, then deploy the code that writes the new data.
2. To add a required field or a stricter rule: apply it with `validationLevel: moderate` (updates to documents that do not match yet are not checked), backfill the old documents (find them with `{$nor: [validator]}`), then apply it with `strict`. Do the backfill through the writer (`updateWithRetry` with a migration user), so that it increments the version and sets the last-modified pair. If it must be a raw `updateMany`, it must, in the same update, do `$inc` of `_documentMeta.version` by `1L` (if not, a caller that holds the old version writes over the backfilled value) and `$set` of `_lastModifiedDate` and `_lastModifiedBy` (if not, they name the last user, not the migration).

With `strict`, an update of an old document that does not match the new validator fails with code 121, also a `cancel`.

## 8. Tests

Use one shared Testcontainers MongoDB. It is a single-node replica set (`withReplicaSet()`; Testcontainers 2.x is standalone otherwise). Before each test, drop the collection and create it again. Concurrency tests release all workers at the same time and assert on each result.

| ID | Given / When | Then |
|---|---|---|
| T1 | Create | Version 1; who-columns set |
| T2 | Amend with current version | Version 2; change stored; created fields unchanged; `_lastModifiedBy` = amender |
| T3 | Amend with stale version | Conflict; document unchanged |
| T4 | Amend a `SUBMITTED` order with its current version | Invalid status, not conflict |
| T5 | Amend a missing id | Not found |
| T6 | Submit a draft; submit it again with the new version | First succeeds; second invalid status |
| T7 | Cancel `DRAFT`, cancel `SUBMITTED`, cancel `CANCELLED` | Two succeed; third invalid status |
| T8 | 16 pods amend the same order at the same version | Exactly 1 success, 15 conflicts; version +1 |
| T9 | One pod submits, one cancels, same version | Exactly one wins; final status matches the winner |
| T10 | 8 pods × 50 rounds on 5 orders: read the order, amend `amount` to the read value + 1, reload on conflict and try again | Conflicts occurred; for each order, version − 1 = total successful writes and amount = 1250 + total successful writes (a lost update would lose an increment); created fields unchanged |
| T11 | Raw insert missing `_createdBy`, or with an extra field in `_documentMeta` | Rejected by the validator |
| T11c | Raw insert with an extra field in `creditor.address`, or an unknown `status` | Rejected by the validator |
| T11d | Raw insert with an empty `_createdBy` | Rejected by the validator |
| T11e | Raw insert with `amount` 0 | Rejected by the validator (`amount` must be more than 0) |
| T12 | `updateCreditorAddress` | Only `creditor.address` changes; the creditor's name and IBAN, and every other field, stay unchanged |
| T13 | One pod updates the address, another amends `remittance`, same version | Exactly one wins, the other gets a conflict; the version guards the whole document |
| T13b | Alice's change conflicts with Bob's change to a different field. She reloads, checks the field that she changed against the base value that she read (CONFLICTS.md, "Merge"), and sends her change with the new version | The check passes; version 3; both changes are kept; `_lastModifiedBy` is alice |
| T16 | `PaymentOrderClients` with and without URI options | Defaults apply; URI options override only what they set; no `timeoutMS` default when the URI sets `timeoutMS` or `socketTimeoutMS`; `waitQueueTimeoutMS` alone keeps the default; `retryWrites` always on; `appName` defaults to the pod name unless the URI sets it; a JMX connection pool listener is registered; BSON dates decode as `Instant` |
| T17 | Create, then amend, with clocks that have sub-millisecond parts | `_createdDate` and `_lastModifiedDate` are BSON dates with the clock's instants without the sub-millisecond part; they read back as `Instant` |
| T18 | Amend a creditor with no address; update an address with no country | `OrderValidationException` from the service with the field error; database not touched; document unchanged |
| T19 | `insert` with a system field in its input | `IllegalArgumentException`; nothing inserted |
| T14 | 16 pods × 20 system increments of `amount` on one order via `updateWithRetry` with `RetryPolicy.defaults()` | Every call succeeds or throws `RetriesExhaustedException`; the retry path ran; version − 1 = successes; amount = 1250 + successes |
| T15 | `updateWithRetry` on a `CANCELLED` or missing order; on an order a user changed | Invalid status / not found on the first attempt, without a call to `recompute`, no retry; otherwise recomputed from the fresh document |
| T23 | Service checks (unit) | Every problem reported at once; amend values type-checked; address checks |
| T24 | Service checks vs. MongoDB schema | Same required fields; for each one, both the service and the schema reject an order missing it |
| T24b | Service checks vs. MongoDB schema, field formats | For each format rule (empty required string, lower-case currency, bad IBAN, a currency or IBAN with a final newline, a name or remittance text over 140 characters), both the service and the schema reject the order |
| T31 | `apply` on an existing collection with an older validator and a document that does not match the new rules | `moderate`: the old document can still be updated; `strict`: its update fails with code 121; new inserts must match the new validator |
| T25 | `writer.update` with a change to `_createdBy` or `_documentMeta.version`, or with `$rename` (also into `_createdBy`) | `IllegalArgumentException`; nothing written |
| T26 | `get` for an existing order and for a missing id | Returns the stored order (with its version); returns nothing for the missing id |
| T27 | Thread interrupted while `updateWithRetry` waits between attempts | `NotSentException` (the next attempt was not sent); not a version conflict |
| T28 | `recompute` returns `null` | No write; the method returns the current version; the version does not change |
| T30 | `PaymentOrderService.updateWithRetry` | Applies the system change through the service; version +1 |
| T29 | `updateWithRetry` where each attempt conflicts | One read for each attempt and one diagnosis read at the end; then `RetriesExhaustedException` |
| T23b | `amount` of 0 or less (service), and a raw insert with `amount` 0 (validator) | Field error `amount`; rejected by the validator |
| T20 | Amend a document that has no `_documentMeta.version` (validator removed to create it) | `IllegalStateException`; the version is never defaulted; document unchanged |

T10 is the proof that no update is lost.

`mvn verify -Pstress` runs `StressIT`. The default build does not include it. The test uses many pods and threads, with a mix of user amends and system retries. You can set the number of orders and the duration. User writes and system writes both increment `amount` from the value that they read (user writes reload with `get`). For each order, the test asserts the T10 invariant (version − 1 = successful writes; amount = 1250 + successful writes), and that conflicts occurred. It reports user and system latency separately, and only after a warm-up period. It also checks these conditions: the created pair is unchanged, some writes succeeded, and there are no unexpected exceptions. It reports throughput, conflicts, retries and latency. A transient failure causes the run to fail, unless `-Dstress.allowTransient=true` is set (for runs during an Atlas failover). In that case, the invariant becomes a range: successful writes ≤ version − 1 ≤ successful + outcome-unknown writes. The parameters are in OPERATIONS.md.

## 9. Transient error mapping

The optimistic locking behavior in sections 1 to 8 does not change.

### 9.1 Goal

Callers of `PaymentOrderService` see only the exceptions of this project. They do not see raw driver exceptions, with one exception: `MongoSecurityException` (an authentication failure) goes to the caller as it is. It is a configuration fault, and a retry cannot correct it. They can identify three different conditions:

1. **Business outcome:** not found, version conflict, invalid status (existing, unchanged).
2. **Validation failure:** the server rejected the document (error code 121). The write did not occur.
3. **Transient failure:** an infrastructure problem (election, network, timeout, pool exhaustion). The caller can retry later.

### 9.2 Transient failures say whether the write may have happened

A transient failure is one of two exceptions. Both extend the sealed `TransientDataAccessException`. The type tells the caller what happened to the data. When the classification knows the reason, it throws a subtype that names it. It does not use the general type for a case that has its own type.

| Kind | Example | Exception |
|---|---|---|
| Never sent | Server selection timeout, pool checkout timeout (without `timeoutMS`), pool cleared, socket open failure, operation on a closed client, interrupt during the wait in `updateWithRetry` | `NotSentException` (a `NotWrittenException`): the write did not happen |
| Sent, response lost | Connection dropped after sending, socket read or write failure, socket read timeout, interrupted thread | `ResponseLostException` (a `MaybeWrittenException`): the write may have happened |
| `timeoutMS` expired on a write | Any expiry of the operation deadline, also in server selection or pool checkout | `WriteTimeoutException` (a `MaybeWrittenException`): the driver can retry several times before the deadline, so an earlier attempt may have applied |
| Applied, not confirmed | Write-concern error | `WriteNotConfirmedException` (a `MaybeWrittenException`): the primary applied it |
| Rejected by the server after the driver's retry | NotWritablePrimary, NodeIsRecovering, other `RetryableWriteError` | `RetriedWriteException` (a `MaybeWrittenException`): the driver reports only the retry's error, so the first attempt may have applied |
| `timeoutMS` expired on a read | The diagnosis read, the read in `updateWithRetry`, `read` | `ReadTimeoutException` (a `NotWrittenException`): nothing was written |
| Server overloaded, after the driver's retries | `SystemOverloadedError` label | `ServerOverloadedException` (a `MaybeWrittenException`): an attempt may have applied |
| Any other failed read | Diagnosis read, the read in `updateWithRetry` | `NotWrittenException`, or `NotSentException` if it was never sent: nothing was written |
| No known reason | A driver exception that fits no row | `MaybeWrittenException` on a write, `NotWrittenException` on a read, with a `WARN` |

Both apply to updates and inserts. For an insert, the exception contains the generated `_id`. The caller can use this id to find the order, and does not create the order again.

After `NotWrittenException`, the caller can send the same write again. After `MaybeWrittenException`, the caller must reload the order before it retries. The caller must read with `ReadConcern.MAJORITY`. Then it does not see a write that the database can still roll back. If the version changed and the stored change is the caller's change, the write was successful.

### 9.3 Rules

- The mapping occurs in one place: the component that already does all writes. Reads that supply data for a write (in `updateWithRetry` and diagnosis) use the same mapping.
- Classify by driver exception type and error labels. Verify the exact classes and labels for the pinned driver version. The tests in 9.5 are the contract, not a specified class list.
- The original driver exception stays as the cause.
- Do not add new retries. The driver's built-in retryable writes stay on, and they handle a single transient failure. The client factory forces `retryWrites=true`, because the `RetriedWriteException` reasoning depends on it. The application does not retry a user write after a transient failure. `updateWithRetry` retries only version conflicts. Any transient failure, including `MaybeWrittenException`, stops it. The reason is that a new calculation after a write that possibly occurred can apply the write two times.
- The writer pins `WriteConcern.MAJORITY` and primary reads on its collection. Client and URI settings do not change this. Thus, an acknowledged write stays after a failover, and the diagnosis read is always current.
- The writer rejects changes that touch system fields (`_id`, `_documentMeta`, the who-columns), and all operators other than `$set`, `$unset` and `$inc`, with `IllegalArgumentException`. The writer sets the last-modified pair and the version itself.
- The writer maps non-driver exceptions from a write only in these cases. A document that is larger than the BSON size limit becomes a validation exception. This includes an update that the server rejects because the document after the update would be larger than the limit. The message of a size failure says that the document is larger than the 16 MB BSON document limit, and its cause is the driver exception (`BsonMaximumSizeExceededException` from the driver, or the server's write error 10334). A value with no BSON codec becomes `IllegalArgumentException` (a programming error). An operation on a closed client is `NotWrittenException`. The writer does not catch other programming errors. If an amount does not fit `Decimal128` (more than 34 significant digits), the service's checks give a field error.
- `MongoSecurityException` is not mapped. It goes to the caller as it is, also when it is the cause of a different driver exception.
- A write error (a `writeErrors` entry) without an error label, other than 121, 10334 and 11000, is a bad request: for example code 14 (`$inc` on a string), 40 (conflicting paths), or 2 (a bad filter, an `$inc` overflow). It becomes `IllegalArgumentException` with the driver exception as cause, not a transient exception: nothing applied. The driver never retries because of a write error, and a retry of a write that already applied returns the same result, not a write error (the retryable-writes specification).
- A read error without an error label and with code 2 (BadValue) or 9 (FailedToParse) is a bad filter, for example a malformed precondition in `updateWithRetry`. It becomes `IllegalArgumentException`, before `recompute`. All other read errors stay `NotWrittenException` (for example a stepdown): the list of bad-request codes is short on purpose, so an unknown error is never treated as final.
- A server error with code 13 (Unauthorized: the user has no permission for the operation) becomes `NotAuthorisedMongoException`, on writes and reads. It extends `DataAccessException`, not the transient type, because a retry cannot correct it. A write-concern error is not this case, whatever its code: the primary applied the write, so it stays `WriteNotConfirmedException`.
- An error with the `SystemOverloadedError` label (the server sheds load; the driver already retried it) is `ServerOverloadedException`, without a `WARN` stack trace.
- Do not ignore a driver exception that fits no category. Wrap it in `MaybeWrittenException` (a read: `NotWrittenException`). Log it at `WARN`, so that a developer can add it to the classification later.

### 9.4 Test setup: fail points

MongoDB's `failCommand` fail point causes the errors. This fail point needs test commands enabled on the test container:

```java
new MongoDBContainer("mongo:9.0.4")
        .withReplicaSet()   // Testcontainers 2.x is standalone without it
        .withCommand("--replSet", "docker-rs", "--setParameter", "enableTestCommands=1")
```

Keep the replica-set flag that the container needs. Check this against the Testcontainers version that you use. Fail points are only for tests. Do not enable them outside the test container.

Each test arms a fail point through the admin database.

### 9.5 Tests (`ErrorMappingIT`)

| ID | Trigger | Then |
|---|---|---|
| E1 | `amend` with a creditor that has no address | Validation exception; document and version unchanged |
| E2 | `failCommand` on `update`, `errorCode: 10107` (NotWritablePrimary), `times: 1` | Driver retries; `amend` succeeds; version +1 |
| E3 | Same, `times: 2` | `RetriedWriteException`; version unchanged (both attempts were rejected, which only a reload can tell the caller) |
| E4 | `failCommand` on `update`, `closeConnection: true`, `times: 2` | `ResponseLostException` |
| E5 | `failCommand` on `update`, `blockConnection: true`, `blockTimeMS` above a short `socketTimeoutMS` set on the test client | `ResponseLostException` |
| E6 | Client with `maxPoolSize=1`, `waitQueueTimeoutMS=200` and `socketTimeoutMS=0` (legacy timeouts, no `timeoutMS`); one thread's update is blocked by a fail point while a second thread updates | Second thread gets `NotSentException` |
| E7 | After E4: reload the order (majority read) | Not applied: `closeConnection` fires before the update runs; the caller can retry |
| E7b | `blockConnection` past a short `socketTimeoutMS` on both attempts; wait out the block, reload (majority read) | Applied: version moved and the stored change is the caller's, although the caller got `ResponseLostException` |
| E8 | `failCommand` on `update` with a `writeConcernError` (code 91), `times: 2` | `WriteNotConfirmedException`; the primary applied it (version +1) |
| E9 | Client `timeoutMS` shorter than a `blockConnection` on `update` | `WriteTimeoutException`, cause `MongoOperationTimeoutException` |
| E10 | `failCommand` on `find`, `closeConnection: true`, `times: 2`, hitting the read in `updateWithRetry` | `NotWrittenException`; nothing written; `updateWithRetry` stops |
| E11 | `failCommand` on `insert`, `errorCode: 10107`, `times: 2` | `RetriedWriteException`; carries the generated id; no such order exists |
| E12 | As E6 but with `timeoutMS` instead of `waitQueueTimeoutMS` | Second thread gets `WriteTimeoutException` (the classification does not try to tell an earlier attempt from the last one) |
| E13 | Service call after its client is closed | `NotSentException`; nothing written |
| E14 | `writer.update` with a 17 MB value, which the driver refuses to send (the service's 140-character limit stops this earlier) | `OrderValidationException`; the message says that the document is larger than the 16 MB limit; the cause is the driver's `BsonMaximumSizeExceededException`; nothing written |
| E15 | `writer.update` with a value that has no BSON codec | `IllegalArgumentException` |
| E16 | As E3, with the default `timeoutMS` | The driver keeps retrying within the deadline; `amend` succeeds; version +1 |
| E17 | Service call with credentials that the server rejects | `MongoSecurityException` goes to the caller as it is; nothing written |
| E18 | `failCommand` on `update`, `errorCode: 13` (Unauthorized), `times: 1` | `NotAuthorisedMongoException` with the driver exception as cause; not transient; version unchanged |
| E19 | `failCommand` on `update` with a `writeConcernError` of code 13, `times: 1` | `WriteNotConfirmedException` (not `NotAuthorisedMongoException`); the primary applied it (version +1) |
| E20 | `failCommand` on `update` with the labels `SystemOverloadedError` and `RetryableError`, more times than the driver retries | `ServerOverloadedException`, not `RetriedWriteException` |
| E21 | Client `timeoutMS` shorter than a `blockConnection` on `find`, hitting the read in `updateWithRetry` | `ReadTimeoutException`, cause `MongoOperationTimeoutException`; nothing written |

With `timeoutMS` (the default), the driver retries a write until the deadline, not only one time. Thus, the driver continues past `times: 2` fail points (E16). Some tests classify the error that remains after a single retry (E3, E4, E7, E10, E11). These tests use a client that has a legacy timeout in its URI. This turns off the default `timeoutMS` for that client.

Each test also asserts that no raw driver exception gets to the caller, except E17, where `MongoSecurityException` must get to the caller as it is.

## 10. Graceful shutdown

### 10.1 Goal

When the process gets an instruction to stop, the writes that are in progress finish. The service refuses new writes with the shutting-down exception. The `MongoClient` closes exactly one time.

### 10.2 Behavior

- A small lifecycle component owns the `MongoClient` and the service.
- `shutdown(Duration grace)`:
  1. Immediately stops new calls. A service call after this point throws a shutting-down exception. It does not touch the database.
  2. Waits until in-flight calls finish, for a maximum of `grace`.
  3. Closes the `MongoClient`, also if the grace period ran out. Closing does not stop a call that already uses a connection (driver 5.13 behaviour, selected instead of an interrupt of caller threads). That call finishes independently, within `timeoutMS`, and reports its result normally.
  4. Is idempotent and thread-safe: a second call does nothing. It waits until the first call is complete, then returns. Thus, a JVM shutdown hook that calls `shutdown` second does not let the JVM stop during the drain.
- `registerShutdownHook(Duration grace)` registers a JVM shutdown hook that calls `shutdown`.
- In-flight tracking uses a counter or a similar concurrency primitive in the process. The tracking is only for draining. It is not for the correctness of writes.

Kubernetes settings (termination grace period, preStop hooks) are out of scope. OPERATIONS.md tells you that the Kubernetes grace period must be more than the `preStop` sleep plus `grace` plus `timeoutMS`.

### 10.3 Tests (`ShutdownIT`)

| ID | Given / When | Then |
|---|---|---|
| S1 | An `amend` is in flight, held by a `blockConnection` fail point for 500 ms; `shutdown(5s)` is called during it | The `amend` completes successfully; `shutdown` returns after it; the client is closed |
| S2 | After `shutdown` | Every service call (also `get` and `updateWithRetry`) throws the shutting-down exception; nothing is written |
| S3 | An `amend` is held for 3 s; `shutdown(500ms)` | `shutdown` returns after about 500 ms and the client is closed once; the held `amend` is not aborted, finishes within `timeoutMS` and reports its result normally (here it succeeds, version +1) |
| S4 | `shutdown` called twice, including from two threads at once; and a second call while the first one drains an in-flight call | No error; the client is closed once; the second call returns only after the client is closed |

## 11. Out of scope

HTTP status mapping (comes with the API), application-level retries for transient errors, Kubernetes manifests, metrics. The generic writer has no delete operation, no array operators by default, no `arrayFilters` and no transactions (PORTING.md tells you what to add).

Deferred: a client-supplied idempotency key for `create` (with a unique index). With this key, a repeated create can return the existing order. It does not insert a duplicate.

## 12. Generic writer

The generic writer is a component in the package `com.example.optimistic`. All inserts and updates to the collection go through it. It does two jobs, with the safety rules that keep both correct:
1. **Optimistic locking:** the version filter, `$inc` of the version, the diagnosis read, and the system retry.
2. **Who-columns:** an insert sets all four from one instant; an update sets only the last-modified pair; nothing writes the created pair after the insert. The who-columns record the last writer, not a history. The version orders writes; the dates come from the pod's clock and are for people.

The safety rules serve both jobs: the writer owns six field names (`_id`, `_documentMeta` and the four who-columns), it accepts only the operator allow-list, it pins majority writes and primary reads, and it classifies driver errors. The writer has no payment-order types. Any collection with the field names of section 3 can use it. The payment-order code uses it through a facade.

### 12.1 Contract

`OptimisticLockingWriter<K>` (`K` is the type of `_id`) on a `MongoCollection<Document>`, with an optional `InstantSource`:

| Operation | Behaviour |
|---|---|
| `insert(K id, Document fields, String user)` | The caller supplies `_id`, not null. Sets the version to `1L` and the four who-columns. Rejects system fields in `fields`, also as the first segment of a dotted key (`_createdBy.x`). A duplicate `_id` (error 11000) throws `DuplicateKeyException`, not a transient exception. |
| `update(K id, long expectedVersion, Bson precondition, Bson changes, String user)` | One `updateOne` with the filter `{_id, version: expectedVersion}` plus `precondition`, and the changes plus the last-modified pair plus `$inc` of the version by `1L`. On a zero match, one read: missing → `DocumentNotFoundException`; version differs → `VersionConflictException`; otherwise → `PreconditionFailedException` (with the current document). Before it reports a failed precondition, one read with `{_id}` plus `precondition` makes sure that the document does not match it now; if it matches (a rollback or a race), the result is `VersionConflictException`. |
| `updateWithRetry(K id, Bson precondition, Function<Document, Bson> recompute, String user, RetryPolicy policy)` | Section 2, with `precondition` instead of the status set. Each attempt reads with `{_id}` plus `precondition`. If that read finds nothing, a read by `_id` tells not found from precondition failed, before `recompute`. |
| `read(K id)` | `ReadConcern.MAJORITY` from the primary. |

- `user` must not be null or blank, in each operation: it becomes a who-column. A violation throws `IllegalArgumentException` before any database call.
- A stored version must be an integral number. `readVersion` accepts a `long`, an `int` or a whole `double` (a value written by mongosh or a migration), and throws `IllegalStateException` for any other value (for example `1.5` or `"1"`).
- `precondition` is an extra filter, or `null` for none. Use it for a status rule, a guard, or a shard key (on a sharded collection, put the shard key in it, so that each update goes to one shard). With `null`, a zero match at the expected version is reported as a conflict.
- The field names are those of section 3: `_documentMeta.version` and the four who-columns.
- The rules of sections 3 and 9 apply to the generic writer: pinned majority writes and primary reads, the operator allow-list, the error classification, no retry of a write on a transient failure. The error classification is in its own class, `MongoErrorTranslator`.
- The generic exceptions are in `com.example.optimistic.exception`, and the domain exceptions in `com.example.paymentorders.exception`. The generic exceptions extend `DataAccessException`. `OptimisticWriteException` is sealed: its subclasses are exactly `DocumentNotFoundException`, `VersionConflictException`, `PreconditionFailedException` and `RetriesExhaustedException`, so a caller can switch over them exhaustively. `TransientDataAccessException` is sealed to `NotWrittenException` and `MaybeWrittenException`. `NotWrittenException` is sealed to `ReadTimeoutException` and `NotSentException`. `MaybeWrittenException` is sealed to `WriteTimeoutException`, `RetriedWriteException`, `WriteNotConfirmedException`, `ResponseLostException` and `ServerOverloadedException`. `DataAccessException` stays open, so that each domain can add its own exceptions (for example `ShuttingDownException`). The domain exceptions extend them: `OrderNotFoundException` extends `DocumentNotFoundException`, `InvalidStatusException` extends `PreconditionFailedException`, `OrderValidationException` extends `SchemaValidationException`.
- `PaymentOptimisticWriter` is the payment-order facade, and the example for other collections: it makes the `ObjectId` ids, gives the status set as the precondition, and catches the three generic exceptions above to throw their domain subclasses, with the generic exception as the cause. The other exceptions pass through.

### 12.2 Tests (`OptimisticLockingWriterIT`; G12–G19 in `WriterEdgeCasesIT`)

These tests use a minimal collection (`widgets`), string ids and no validator, so that they check the generic writer alone.

| ID | Given / When | Then |
|---|---|---|
| G1 | Insert with a caller-supplied id; insert the same id again | Version 1, who-columns set; the second insert throws `DuplicateKeyException`; one document; `read` returns the dates as `Instant`, also through a client with the driver's default codecs |
| G2 | Update with the current version; update again with the old version | Version 2; then `VersionConflictException` (actual 2); the caller's `changes` object is not changed |
| G3 | Update with a precondition (a guard) that the document does not meet; the same through `updateWithRetry` | `PreconditionFailedException` with the current document (not `RetriesExhaustedException`); `recompute` is not called; version unchanged |
| G4 | Update a missing id | `DocumentNotFoundException` |
| G5 | 8 threads × 25 increments of a counter field through `updateWithRetry` | Every call succeeds or throws `RetriesExhaustedException`; counter = successes; version − 1 = successes |
| G6 | A change to a system field, or with `$rename` | `IllegalArgumentException`; nothing written |
| G7 | A precondition that names a shard-key-like field (`tenant`), with the right and the wrong value | Right value: version +1; wrong value: `PreconditionFailedException` |
| G8 | `recompute` returns `null` | No write; the current version |
| G9 | The update matches nothing because the precondition is false; before the diagnosis read, a different writer makes the precondition true again, at the same version | `VersionConflictException`, not `PreconditionFailedException` |
| G10 | An update (9 MB) that makes the stored document (9 MB) larger than 16 MB | `SchemaValidationException`, not transient; the message says that the document is larger than the 16 MB limit; the cause is the server's `MongoWriteException` with code 10334; the document is unchanged |
| G11 | `RetryPolicy` limits (unit test) | Defaults: the wait limits after attempts 1 to 7 are 5, 10, 20, 40, 80, 100, 100 ms; a large attempt number does not overflow; `maxAttempts < 1` and negative delays are rejected |
| G12 | A null or blank `user` in `insert`, `update` or `updateWithRetry`; `insert` with a null id; `insert` with the keys `_documentMeta.version` or `_createdBy.x` | `IllegalArgumentException`; nothing written |
| G13 | A stored version of `int` 1, `double` 1.0, `double` 1.5, or `"1"`; then an update at version 1 | `int` and whole `double`: version 2; `1.5` and `"1"`: `IllegalStateException`, document unchanged |
| G14 | Clocks at 1969-07-20T20:17:40.123Z and 9999-12-31T23:59:59.999Z; a date in a subdocument and in an array | The dates read back exactly as `Instant`; a date inside an array reads back as `java.util.Date` (the known limit of the codec) |
| G15 | An empty change; `_id` `int` 1 updated as `long` 1, then inserted as `double` 1.0; an update on a thread that is already interrupted | Empty change: version +1; the numeric ids are one document (`DuplicateKeyException`); interrupted: `ResponseLostException`, nothing applied |
| G16 | Updates that the server rejects: `$inc` on a string (14), `$set` and `$inc` on one path (40), a bad precondition filter (2), `$inc` of a version at `Long.MAX_VALUE` (2); and the code-14 update after a dropped connection (`failCommand` `closeConnection`, `times: 1`), so that the error comes from the driver's retry | `IllegalArgumentException` with the server's `MongoWriteException` (that code) as cause; not transient; the document is unchanged |
| G17 | Hostile calls: `update` with `changes` null; `expectedVersion` 0 and −1; a `recompute` that throws; a `recompute` that changes a system field; a `RetryPolicy` with a null delay; a writer on a null collection | `IllegalArgumentException` for null `changes` and the system field (`recompute` called once); `VersionConflictException` for 0 and −1; the `recompute` exception unchanged; `NullPointerException` at construction for the policy and the collection; nothing written |
| G18 | `updateWithRetry` with a malformed precondition (`$noSuchOperator`); then a read that fails with ShutdownInProgress (91) on the first attempt and on the driver's retry | Malformed: `IllegalArgumentException` with the server's code-2 exception as cause, `recompute` not called; 91: `NotWrittenException`; nothing written |
| G19 | A driver exception that fits no case (a plain `MongoException` without a code or a label), on a write and on a read | Write: `MaybeWrittenException`, not a subtype; read: `NotWrittenException`, not a subtype; the cause is the driver exception |
