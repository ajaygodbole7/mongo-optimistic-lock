# Errors: what a caller can get, and what to do

This document lists each exception that a caller of the writer or the payment-order service can get.

- **Two kinds are not wrapped:** `MongoSecurityException` (authentication failed), and programming errors
  (`IllegalArgumentException`, `IllegalStateException`).
- **All other exceptions** extend `DataAccessException`.
- **The four no-match outcomes** are the sealed `OptimisticWriteException`: `DocumentNotFound`, `VersionConflict`,
  `PreconditionFailed` and `RetriesExhausted`. A `switch` over them needs no `default`.
- **Domain names in brackets** are the subclasses that the payment-order service throws.
- **DESIGN.md section 6** gives the classification of each driver failure.

| Exception | Meaning | What to do |
|---|---|---|
| `VersionConflictException` | A different writer changed the document | Reload, then decide again (CONFLICTS.md) |
| `DocumentNotFoundException` (`OrderNotFoundException`) | No document has the id | Return 404 |
| `PreconditionFailedException` (`InvalidStatusException`) | The version is correct, but the precondition (for example the status) is false | Tell the caller. Do not retry |
| `SchemaValidationException` (`OrderValidationException`), `reason()` = `FIELD_ERRORS` | The service's own checks failed. `errors()` lists every problem | Return 400 |
| `SchemaValidationException`, `reason()` = `REJECTED_BY_VALIDATOR` | The database validator refused the document. The cause is the driver exception | Return 400. From a service call, report a bug: the checks and the schema disagree |
| `SchemaValidationException`, `reason()` = `TOO_LARGE` | The document is over 16 MB. The cause is the driver exception | Return 413 |
| `DuplicateKeyException` | The id exists (error 11000) | Look up the existing document |
| `TransientDataAccessException`, `outcomeUnknown()` = `false` | An infrastructure failure. The write did not happen | Retry later |
| `TransientDataAccessException`, `outcomeUnknown()` = `true` | An infrastructure failure. The write may have happened | Reload first, with `ReadConcern.MAJORITY`. If the version moved and the change is yours, the write worked |
| `OperationTimeoutException` (a transient subclass) | The `timeoutMS` deadline expired. On a write, the outcome is always unknown | As for `outcomeUnknown()` = `true` |
| `RetriedWriteException` (a transient subclass) | The driver retried, and the server rejected the retry. The outcome is always unknown | As for `outcomeUnknown()` = `true` |
| `RetriesExhaustedException` | `updateWithRetry` used all its attempts. It is not a `VersionConflictException` | Requeue the work |
| `NotAuthorisedMongoException` | The database user has no permission (code 13) | Correct the roles. Do not retry |
| `ShuttingDownException` | The pod is shutting down. It refused the call before it touched the database | Retry on another pod (for example, return 503) |
| `MongoSecurityException` (not wrapped) | Authentication failed. On a driver retry after a first attempt that applied, the write may have happened | Fix the credentials. Reload before you write again |
| `IllegalArgumentException`, `IllegalStateException` | A programming error: a system field in a change, an operator outside the allow-list, a value with no codec, a document without a version, an update or a filter that the server rejects (for example `$inc` on a string, an unknown query operator). Nothing was written | Fix the code |
