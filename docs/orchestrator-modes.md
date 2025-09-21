# Orchestrator Modes & Feature Switches

The orchestrator starter exposes a set of modes that control how records are processed, persisted, and acknowledged. Use this guide to understand each mode and how to toggle it at runtime.

## How to Change Modes

All properties live under the `orchestrator.*` prefix. You can set them in `application.yml` or via environment variables (Spring Boot converts `ORCHESTRATOR_FAILURE_MODE=SKIP_AND_LOG`, etc.). Update the value and restart the app for the change to take effect.

## Failure Processing Modes

| Property | Values | Default | Behavior |
|----------|--------|---------|----------|
| `orchestrator.failure-mode` | `ATOMIC`, `SKIP_AND_LOG` | `ATOMIC` | `ATOMIC` wraps transformations and produce/commit in a single Kafka transaction; failures roll back the offset and the record stays in the topic. `SKIP_AND_LOG` processes each record independently, logs failures after `non-atomic-max-attempts`, and advances the offset so the flow keeps moving. |
| `orchestrator.non-atomic-max-attempts` | integer >= 1 | `3` | Applies only when `SKIP_AND_LOG` is active. Controls how many retries run before a record is marked failed and skipped. |
| `orchestrator.commit-batch-size` & `orchestrator.commit-flush-interval-ms` | integers | `50`, `200` | Tune the batched acknowledgement manager used in non-atomic flows so small batches flush quickly and busy partitions stay efficient. |

## Database Reliability Modes

| Property | Values | Default | When to use |
|----------|--------|---------|-------------|
| `orchestrator.db-strategy` | `OUTBOX`, `RELIABLE`, `LIGHTWEIGHT`, `NONE` | `RELIABLE` | `OUTBOX` inserts a row before processing to guarantee dedup on restart (requires healthy DB). `RELIABLE` logs only outcome rows (success/failure) and is the balanced default. `LIGHTWEIGHT` stores only failure rows asynchronously; successes skip DB writes. `NONE` disables DB persistence entirely (no metrics, no retries). |
| `orchestrator.error-table` | table name | `event_failures` | Rename when your schema differs. Applies to all strategies except `NONE`. |
| `orchestrator.store-payload` | `NONE`, `BYTES`, `TEXT` | `NONE` | Controls whether the original payload is persisted alongside failure metadata. `BYTES` preserves raw UTF-8, `TEXT` stores a decoded string. Combine with `store-payload-on-failure-only`. |
| `orchestrator.store-payload-on-failure-only` | `true`, `false` | `true` | When `true`, payload is captured only for failures. Set to `false` to always archive payloads (larger tables). |

## Database Resilience Controls

| Property | Default | Meaning |
|----------|---------|---------|
| `orchestrator.db-timeout-ms` | `150` | Millisecond timeout for JDBC calls before they are considered degraded. Lower to fail fast; increase if network/DB latency is higher. |
| `orchestrator.db-degrade-dont-block` | `true` | When `true`, failed DB calls log a warning but the flow keeps moving (circuit breaker kicks in). Set `false` to propagate DB failures and halt processing. |
| `orchestrator.db-circuit-failure-threshold` | `5` | Number of consecutive DB failures before the in-memory circuit opens. |
| `orchestrator.db-circuit-open-ms` | `10000` | How long (ms) the circuit stays open before retrying DB work. |
| Metric hooks | Gauge names | Description |
|--------------|------------|-------------|
| `orch.db.circuit.open` | 0/1 | Shows whether the DB circuit breaker is currently open. |
| `orch.db.degraded` | 0/1 | Indicates the orchestrator is in degraded mode because of DB timeouts/failures. |

## Batching & Concurrency

- `orchestrator.batch-enabled` (default `true`): when `true`, the app uses the `batchContainerFactory` listener and processes records in batches. Set to `false` to switch to single-record processing (`recordContainerFactory`).
- `orchestrator.consumer-concurrency` (default resolves to CPU cores or `6` via env): controls how many Kafka listener threads (and transactions) run in parallel. Set `CONCURRENCY=1` for ordered processing per partition or increase to match partitions.
- `spring.kafka.consumer.max-poll-records` (default `500`, override via env or customizer): higher numbers improve batching but require more heap and faster downstream IO.

## Deduplication & Outbox Lifecycle

When `db-strategy=OUTBOX`, each message is inserted as `status=RECEIVED` with a dedup key before transformation. Success transitions it to `SUCCESS`; failures flip to `FAILED`. The dedup key also travels as a Kafka header (`orchestrator-dedup-key`).

- Use `FailureTracker.markTimedOutReceivedAsFailed` via the scheduled task (from `TimeoutSafeguard`) to mark stuck `RECEIVED` rows as `FAILED`. Configure the timeout through the component if you extend it.
- Ensure the backing table has a unique constraint on `dedup_key` plus indexes on `status` for cleanup jobs.

## Switching Between Modes Safely

1. Plan the change and document the expected impact (e.g., switching to `SKIP_AND_LOG` trades durability for throughput).
2. Update the property (`application.yml` or environment) and redeploy.
3. Watch Micrometer metrics, Kafka lag, and the failure table to confirm the new mode behaves as expected.
4. Roll back by reverting the property and redeploying if error rates or lag spike unexpectedly.

Keep this reference close when you need to rebalance reliability versus throughput in the orchestrator.
