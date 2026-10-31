# Optimistic Locking in MongoDB

Optimistic locking and who-columns for MongoDB, with the safety rules that keep both correct: a
generic writer (`com.example.optimistic`) and a payment-order service on `paymentOrders` built on
it. Java 25, Maven, native MongoDB Java driver (sync); no Spring, no ODM. Requirements are in
@SPEC.md; it wins on any conflict.

## Commands
- Tests: `mvn verify` (needs Colima running; if Testcontainers can't find Docker, stop and tell me)
- Stress: `mvn verify -Pstress`
- Atlas smoke test: `MONGO_URI=... mvn verify -Patlas`

## Rules
- Never write `_createdDate` or `_createdBy` after insert.
- All writes go through `OptimisticLockingWriter` (for `paymentOrders`, through `PaymentOptimisticWriter`): filter on `_id` + expected version, `$inc` the version by `1L`.
- The writer never retries user writes. A caller may send a user write again only after the merge check (CONFLICTS.md). System writes retry only by recomputing from a fresh read.
- Targeted `updateOne` only: no `replaceOne`, transactions, or versioned upserts.
- Java 25 idioms: records, sealed types, pattern-matching switch, `java.time`, no preview features.
- Pin dependency versions; look them up.

## Working style
- State assumptions; ask when unclear.
- Write the minimum code the task needs. No extra features or configuration.
- Touch only what the task needs.
- Prove each change with a test; run `mvn verify` before saying it's done.
- Write docs ~80% ASD-STE100 (short active sentences); keep identifiers and requirement meaning exact.
- Doc layout (README, ONBOARDING): one key idea per bullet; a claim and its reason stay in one bullet, two claims are two bullets. A section opens with at most one framing sentence, then bullets. Sequences are numbered lists. Dense tables become bulleted sections. At most two levels of nesting. Narrative (the README "Why") stays as short paragraphs.