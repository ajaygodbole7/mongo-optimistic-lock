# Operations: configuration, monitoring, shutdown, load and Atlas

This document is for the people who run the payment-order service. README.md gives the overview. DESIGN.md gives the
reasons for each setting.

**Untested.** Nothing in this document has been tried on AWS EKS or MongoDB Atlas. The tests ran only against a local
single-node replica set in Docker. The Kubernetes settings, the Atlas smoke test and the failover procedure are
written down, but nobody has run them.

## Configuration

Each pod gets one `MongoClient` from `PaymentOrderClients.create(connectionString)`. An option in the
connection string overrides the default below. The exception is `retryWrites`, which is always on:

| Option | Default | Why |
|---|---|---|
| `maxPoolSize` | 100 | Must exceed the request threads per pod; size it down with the pod count (see connection budget below) |
| `minPoolSize` | 0 | The minimum applies to every replica-set member, and secondaries get no traffic from this service. The cost: after a failover, each pod builds its pool to the new primary again, two connections at a time. Test an election before you decide |
| `maxConnecting` | 2 | Avoids a thundering herd when pods scale up. It also limits how fast each pod rebuilds its pool after a failover (TLS and SCRAM for each connection). Load-test an election before you change it |
| `timeoutMS` | 10 s | A deadline per operation. Within it the driver keeps retrying a failed write, so writes ride out an election. Not applied when the URI sets `timeoutMS` or `socketTimeoutMS`. With `socketTimeoutMS` and no `timeoutMS` (legacy timeouts), the factory logs a `WARN` |
| `serverSelectionTimeoutMS` | 10 s | Consistent with `timeoutMS`, which caps it anyway |
| `waitQueueTimeoutMS` | 5 s | Pool wait without `timeoutMS` (driver default 120 s). It does not turn off the default `timeoutMS`; with `timeoutMS`, the driver ignores this value and `timeoutMS` limits the wait. If the URI sets `waitQueueTimeoutMS` without `timeoutMS`, the factory logs a `WARN`: to fail faster on a full pool, set a smaller `timeoutMS` |
| `maxIdleTimeMS` | 5 min | Reuse during bursts, prune afterwards |
| `connectTimeoutMS` | 10 s | Fail fast on network trouble |
| `retryWrites` | always `true` | The "outcome unknown" classification depends on it |
| `appName` | pod name (`HOSTNAME`), else `payment-orders` | Atlas, server logs and `currentOp` show which pod opened a connection. The URI can override it |
| pool listener | `JMXConnectionPoolListener` | Pool size, checked-out connections and waiters are JMX MBeans (see Monitoring) |

DESIGN.md section 2 records the decisions behind these defaults: the 10 s deadline against failover times, and the
connection capacity.

The writer also pins `w: majority` and primary reads on its collection. The URI cannot change this. Thus, an
acknowledged write stays after a failover, and the diagnosis read is always current.

## Validator deployment

`PaymentOrderSchema.apply(db)` creates `paymentOrders`, or replaces its `$jsonSchema` validator with `collMod`.

- **Once per release, not in the pods.** Run it as a deployment step: a Kubernetes Job, a Helm pre-upgrade hook or a
  CI step. During a rolling update, old and new pods run at the same time. If each pod ran `apply` at startup, the
  last pod to start would set the validator, and a rescheduled old pod could put the old validator back.
- **Its own credential.** The step needs `collMod` (for example the `dbAdmin` role). The service pods' user needs only
  `readWrite`, so the serving credential cannot change or remove the validator.
- **A looser rule** (a new optional field, a wider format): apply the new validator first, then deploy the code that
  writes it.
- **A stricter schema** (a new required field, a tighter format): follow SPEC.md section 7. Apply it with
  `ValidationLevel.MODERATE`, backfill the old documents (find them with `{$nor: [<validator>]}`), then apply it with
  `STRICT`. With `STRICT` too early, each update of an old document fails with code 121, also a `cancel`. Do the
  backfill through the writer (`updateWithRetry` with a migration user, such as `migration-2026-11`), so that it
  increments the version and sets the last-modified pair. If it must be a raw `updateMany`, it must also do `$inc` of
  the version and `$set` of `_lastModifiedDate` and `_lastModifiedBy`.

## Monitoring

With the default `timeoutMS`, a full connection pool and a slow primary give the same error
(`OperationTimeoutException`). Thus, monitor the pool itself:

- **JMX:** the factory registers the driver's `JMXConnectionPoolListener`. Each pool is an MBean
  (`org.mongodb.driver:type=ConnectionPool,...`) with its size, checked-out connections and waiters. Export it with
  your JMX agent.
- **Alerts:** connections checked out near `maxPoolSize`, waiters above zero for a long time, and checkout waits over
  approximately 50–100 ms.
- **Atlas:** watch `connections.current` against the tier limit. `appName` shows which pod opened each connection.
- **Logs:** `MongoErrorTranslator` logs a `WARN` for each driver error that it cannot classify. One such log means that
  the classification table needs a new row.

## Graceful shutdown

Each pod has one `PaymentOrderRuntime`. It holds the `MongoClient` and the `PaymentOrderService`:

```java
PaymentOrderRuntime runtime = PaymentOrderRuntime.create(System.getenv("MONGO_URI"), "payments");
runtime.registerShutdownHook(Duration.ofSeconds(20));   // runs on SIGTERM
PaymentOrderService orders = runtime.service();
```

`shutdown(grace)` does four things:
1. It immediately refuses new calls with `ShuttingDownException`. It does not access the database.
2. It waits for calls that are in progress, for a maximum of `grace`.
3. It closes the client, also if the grace period ran out.
4. A second call does nothing, but it waits until the first call is complete.

When the client closes, a call that still runs does not stop. Driver 5.13 lets the call finish, within
`timeoutMS` (10 s by default).

**Kubernetes:** when Kubernetes stops a pod, it sends SIGTERM and removes the pod from the Endpoints at the same
time. The load balancers continue to send requests for some seconds. Thus, add a `preStop` hook that waits
before SIGTERM gets to the JVM:

```yaml
lifecycle:
  preStop:
    exec:
      command: ["sleep", "10"]
```

Set `terminationGracePeriodSeconds` to more than `preStop` sleep + `grace` + `timeoutMS` (for example
10 s + 20 s + 10 s → 45 s). Then the traffic stops first, and a call that still runs after the grace period can
finish before the process stops.

## Stress profile

`mvn verify -Pstress` runs only `StressIT`, a load harness. The usual `mvn verify` does not run it. It shows
throughput, conflict rate, system retries and p50/p99 latency. It fails in these conditions: an update is lost,
no write is successful, or an unexpected exception occurs. A transient failure also causes a failure, unless
`stress.allowTransient` is set.

The workers are virtual threads. The latency and throughput figures are meaningful only if the service also runs
its requests on virtual threads. They are not comparable with runs on platform threads. The correctness checks do
not depend on the thread model.

Use system properties to adjust it:

| Property | Default | Meaning |
|---|---|---|
| `stress.pods` | 8 | simulated pods, each with its own `MongoClient` |
| `stress.threadsPerPod` | 16 | concurrent workers per pod (each a virtual thread) |
| `stress.orders` | 20 | orders written to; low = hot orders (conflicts), high = throughput |
| `stress.seconds` | 20 | run length |
| `stress.systemRatio` | 0.2 | share of writes that are system read-modify-writes with retry |
| `stress.mongoOptions` | (none) | connection-string options, e.g. `maxPoolSize=8` |
| `stress.allowTransient` | false | count transient failures instead of failing; for runs spanning a failover |
| `stress.warmupSeconds` | 3 | run time before the measured window; pools fill and the JIT settles |

```sh
mvn verify -Pstress -Dstress.orders=2000 -Dstress.seconds=10 "-Dstress.mongoOptions=maxPoolSize=8"
```

## A load test for a throughput figure

`StressIT` checks correctness under load: no lost update, for user writes and system writes. It does not give a
throughput figure that you can trust for production. All pods and the load generator share one JVM, the load is
closed-loop (each thread waits for its last call), and the database is a local single node. For a throughput
figure:

- **Separate load generators:** several pods or VMs in the same region and zone layout as production, against a
  dedicated Atlas tier of the production size, with the real `PaymentOrderRuntime`.
- **Open-model load:** a constant arrival rate for each operation type, with a tool that corrects for coordinated
  omission (for example HdrHistogram). Increase the rate in steps until p99 or the error rate breaks the service
  objective. That point is the throughput limit.
- **Warm-up first,** and **separate histograms** for user amend, system write, insert and reload. Report attempts per
  success and the exhaustion rate.
- **Two workloads:** many orders with a uniform spread (pool and primary capacity), and hot orders with a skewed
  spread, for example Zipf (conflict and exhaustion curves against writes per second on one order).
- **Lost-update checks on both kinds of write**, as `StressIT` does.
- **Server metrics** at the same time: connections against the tier limit, replication lag, opcounters, queues.
- **Faults during steady load:** a planned stepdown, an unplanned node or zone failure, a pod rollout with SIGTERM,
  pool exhaustion and overload. Assert the range invariant and measure the time to recover.

## What the tests check, and what they do not

The tests run on a single-node replica set. There, a majority write is the same as a local write. There is no
replication delay, no rollback and no real election. Thus, the tests check the logic: the version check, the error
classification and the shutdown sequence. They do not check the behaviour during a real failover, on Atlas, or on
AWS EKS. The failover test against Atlas (below) is written for that, but it has not been run.

## Running against Atlas

The tests use an external cluster only when you tell them to. Two items must both be present: the
`mongo.external=true` property (the `atlas` profile sets it) and the `MONGO_URI` environment variable. Keep
the URI in the environment, not on the command line. The tests then write to a database with a unique name
(`olock_test_<id>`). When the JVM stops, they drop its collection. Thus, the database user needs only `readWrite`.
Fail-point tests (`ErrorMappingIT`) cannot run on Atlas, because Atlas does not allow `enableTestCommands`.
These tests run only locally.

### Smoke test

```sh
export MONGO_URI='mongodb+srv://<user>:<password>@<cluster>.mongodb.net/?retryWrites=true&w=majority'
mvn verify -Patlas
```

`AtlasSmokeIT` does these checks. It makes sure that it has a connection to a writable primary of a replica set.
It runs a create → amend → conflict → submit → cancel lifecycle. It makes sure that the server-side validator is
active. Then 8 pods race on one order, and exactly one wins. Without `MONGO_URI`, the profile fails immediately.

### Failover test (manual, dedicated clusters M10+)

This test checks the transient-error mapping and the retryable writes of the driver during a real election.

1. Start a long stress run on the cluster, with a size for a dev tier:

   ```sh
   mvn verify -Pstress -Dmongo.external=true -Dstress.allowTransient=true -Dstress.seconds=300 \
     -Dstress.pods=4 -Dstress.threadsPerPod=8 -Dstress.orders=200
   ```

2. While it runs, open the Atlas UI and select the **…** menu of the cluster → **Test Resilience** → **Test Primary
   Failover**. Atlas steps down the primary and elects a new one.
3. When the run ends, it must pass. The report shows the number of transient writes (`not applied` /
   `outcome unknown`). With transient failures, the no-lost-updates check becomes a range: successful writes
   ≤ version − 1 ≤ successful + outcome-unknown writes. Latency increases for a short time during the election.
   For a planned stepdown, there are few or no transient failures, because with the default `timeoutMS` the driver
   continues to retry within 10 s.
4. Also do an unplanned failure: in **Test Resilience**, use the node or region outage test, if your tier has it. When a
   primary fails without warning, the secondaries wait approximately 10 s before they start an election. That wait, plus
   the election, is longer than the 10 s `timeoutMS`. Thus, the writes in that period end as `OperationTimeoutException`
   with `outcome unknown`, and the callers must reload. This is a known decision: a longer `timeoutMS` also means a
   longer Kubernetes grace period.

Notes for Atlas:
- **Timeouts:** the default `timeoutMS` of 10 s is the limit for each operation. Without a timeout, a write to a
  primary that stops responding waits until the primary responds again. In a local test with an 8-second freeze,
  the writes waited 8 seconds.
- **Connection budget:** each pod opens a maximum of `maxPoolSize` connections on the primary. It also opens
  2 monitoring connections for each replica-set member. 50 pods with the default `maxPoolSize=100` need
  approximately 5,100 connections. This is more than smaller tiers allow. Set `maxPoolSize` through the URI.
