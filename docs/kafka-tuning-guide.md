# Kafka Producer & Consumer Tuning Guide

This guide lists baseline and scenario-driven overrides for Kafka clients. Apply only the overrides that match your workload and validate under load before promoting to production.

## Producer Scenarios

| Scenario | Key Goals | Suggested Overrides | Notes |
|----------|-----------|---------------------|-------|
| Default high-throughput (current starter) | Balanced throughput and durability | `compression.type=zstd`, `linger.ms=15`, `batch.size=196608`, `enable.idempotence=true`, `acks=all`, `max.in.flight.requests.per.connection=5`, `delivery.timeout.ms=120000` | Fits mixed workloads; aligns with starter defaults. |
| Ultra-low latency / small payloads | Minimise end-to-end latency | Reduce `linger.ms` to `0-5`, shrink `batch.size` to `32768`, keep `compression.type` on but consider `lz4` for lower CPU; ensure network not saturated | Slight throughput trade-off; monitor CPU. |
| Very large payloads (hundreds of KB) | Avoid oversized batches and memory spikes | Increase `max.request.size` and broker `message.max.bytes`, but cap `batch.size` near payload size, ensure `buffer.memory` is large enough | Keep eye on GC and `linger.ms`. |
| Burst traffic with uneven load | Smooth burstiness without backpressure | Raise `linger.ms` to `20-40`, `batch.size` to `262144-524288`, confirm `buffer.memory` is >= 512MB, consider enabling `delivery.timeout.ms` > 180s | Watch broker memory usage. |
| Exactly-once with multiple topics | Guarantee EOS with multi-topic transactions | Keep `enable.idempotence=true`, set `transaction.timeout.ms` based on DB+processing (<900s), reuse long-lived `transactional.id` | Ensure all transactional producers share unique `transactional.id`. |
| Geo-distributed / high latency brokers | Cope with WAN latency | Increase `request.timeout.ms` (e.g. 60s), `delivery.timeout.ms` (180s), allow moderate `linger.ms` (30+) to batch; consider `compression.type=snappy` | Validate network retries do not exceed SLA. |

## Consumer Scenarios

| Scenario | Key Goals | Suggested Overrides | Notes |
|----------|-----------|---------------------|-------|
| Default balanced (current starter) | Read-committed, batch processing | `enable.auto.commit=false`, `isolation.level=read_committed`, `max.poll.records=500`, `fetch.min.bytes=1048576`, `fetch.max.wait.ms=50`, `max.partition.fetch.bytes=5242880` | Provides good batching for medium messages. |
| Low latency acknowledgement | Fast commit as soon as processed | Lower `fetch.max.wait.ms` (to `5-10`), reduce `fetch.min.bytes` (to `< 64KB`), shrink `max.poll.records` (50-100) | Watch CPU & poll frequency. |
| Huge messages / small batches | Avoid memory pressure when payloads are big | Set `max.partition.fetch.bytes` slightly above largest payload, reduce `max.poll.records` (e.g. `10-50`), ensure app heap is sized accordingly | Combine with producer adjustments. |
| Many partitions per consumer | Keep poll loop within `max.poll.interval.ms` | Raise `max.poll.records`, `fetch.max.bytes`, and `max.partition.fetch.bytes`, but verify processing stays below `max.poll.interval.ms`; consider increasing thread pool | Monitor commit latency. |
| Slow downstream dependency (DB, REST) | Prevent rebalance while waiting | Increase `max.poll.interval.ms` (e.g. 10-15 minutes), `session.timeout.ms` (to `30s`), and use manual backpressure (pause/resume) | Consider routing to DLQ when backlog grows. |
| Lightweight retry / DLQ | Minimise blocking retries | Combine with Spring `DefaultErrorHandler` tuning: reduce `max.poll.records`, add `retry.backoff.ms`, use `seekToCurrentErrorHandler` for poison-pill skip | Align with orchestrator failure tracking. |


## Spring Boot 3.4 Structured Logging

Spring Boot 3.4 introduces a first-party JSON formatter. The orchestrator app enables it via:

```yaml
logging:
  structured:
    format:
      console: json
      file: json
  json:
    console:
      enabled: true
    file:
      enabled: true
```

The accompanying `logback-spring.xml` delegates to `org.springframework.boot.logging.logback.JsonLayout`, so the output is ECS-compatible and still honours MDC fields emitted by Micrometer.

## Cross-Cutting Tweaks

- **Failure handling**: Toggle `orchestrator.failure-mode` to `SKIP_AND_LOG` to continue processing when a record fails after `non-atomic-max-attempts` retries; leave it as `ATOMIC` to keep the default all-or-nothing batch transaction.
- **Observability**: Enable `kafka.client.id` with a descriptive name, turn on Micrometer timers (`KafkaTemplate#setObservationEnabled(true)` already done) and export client metrics via JMX or Micrometer.
- **Backpressure**: Monitor `consumer_lag` from Kafka metrics, and add app-level counters such as `orch.records.ok`, `orch.transform.fail` (already provided) to alert when spikes occur.
- **Security**: For TLS/SASL clusters, benchmark with `ssl.endpoint.identification.algorithm=https`, `ssl.protocol=TLSv1.2` or `v1.3` and ensure `sasl.mechanism` matches broker (e.g. `PLAIN`, `SCRAM-SHA-512`). Keep separate config profiles in `application.yml`.
- **Resilience**: When using transactions, prefer `max.poll.interval.ms` < `transaction.timeout.ms`. For idempotent producers without transactions, keep `enable.idempotence=true` and monitor for `OutOfOrderSequence` errors that may signal broker issues.
- **Testing**: Always run targeted load tests (e.g. via `kafka-producer-perf-test.sh`) with realistic payload sizes before adopting new tuning, and watch broker-side metrics for impact on fetch time, I/O, and ISR shrinkage.

## Using the New DB Abstraction

If you plug in an alternative persistence layer (e.g. MongoDB), implement `com.acme.orch.db.FailureTracker`, register it as a Spring bean, and disable the JDBC auto-config by excluding the provided `JdbcFailureTracker`. The orchestrator service only depends on the abstraction, so you can swap in any storage that satisfies the contract.
