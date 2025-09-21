# Kafka Client Tuning Cheatsheet

This guide summarizes the defaults that ship with the orchestrator starter and explains how to adjust producer/consumer settings safely from the orders orchestrator app.

## Baseline Defaults

| Client | Property | Starter Default | Why it matters |
|--------|----------|-----------------|----------------|
| Producer | `enable.idempotence` | `true` | Guarantees ordering and dedup on retries; keep enabled unless the broker forbids it. |
| Producer | `acks` | `all` | Ensures leader + ISR replication before ack. Raise durability; monitor latency. |
| Producer | `compression.type` | `zstd` | Cuts network usage with good ratio; requires Kafka 2.1+. Swap to `lz4` if CPU is tight. |
| Producer | `linger.ms` | `15` | Batches writes for throughput. Lower to `0-5` for latency-sensitive workloads. |
| Producer | `batch.size` | `196608` (192 KiB) | Controls per-partition batch target. Align with average payload size. |
| Producer | `max.in.flight.requests.per.connection` | `5` | Safe with idempotence; prevents reordering during retries. |
| Producer | `delivery.timeout.ms` | `120000` | Upper bound for send retries. Increase if downstream (DB+transform) is slow. |
| Consumer | `enable.auto.commit` | `false` | Offsets are committed inside the orchestrator transaction. Do not enable. |
| Consumer | `isolation.level` | `read_committed` | Reads only committed transactional records. |
| Consumer | `max.poll.records` | `500` | Matches the orchestrator batch pipeline. Increase with caution. |
| Consumer | `fetch.min.bytes` | `1048576` (1 MiB) | Coalesces fetches for throughput. Lower for latency-critical flows. |
| Consumer | `fetch.max.wait.ms` | `50` | Caps server wait for min bytes. |
| Consumer | `max.partition.fetch.bytes` | `5242880` (5 MiB) | Upper bound per partition fetch. Scale with payload size. |

If you do not override anything, the auto-configuration in `orchestrator-starter` applies these defaults for you.

## When to Tweak

Pick the scenario closest to your workload and adjust only the properties listed:

- **Low latency, small messages**: drop producer `linger.ms` to `0-5`, shrink `batch.size` to `32768-65536`, lower consumer `fetch.min.bytes` (`65536`) and `fetch.max.wait.ms` (`5-10`).
- **High throughput, bursty traffic**: raise `linger.ms` to `20-40`, `batch.size` to `262144-524288`, ensure broker `replica.fetch.wait.max.ms` tolerates the extra batching, and consider increasing consumer `max.poll.records` (e.g. `1000`) plus JVM heap.
- **Large payloads (>1 MiB)**: increase producer `max.request.size` and consumer `max.partition.fetch.bytes` a little above payload size, keep `linger.ms` moderate, and decrease `max.poll.records` to protect heap.
- **Geo-distributed brokers**: increase producer `request.timeout.ms` (`60000`), `delivery.timeout.ms` (`180000`), and consumer `fetch.max.wait.ms` (`100`) so retries tolerate WAN latency.

Validate every change with a load test (`load-test/` scripts) and inspect consumer lag plus broker metrics before promoting to production.

## How to Override in the Orchestrator App

1. **Via environment or `application.yml`** – Spring Boot maps properties one-to-one:
   ```yaml
   spring:
     kafka:
       producer:
         linger-ms: 5
         batch-size: 65536
         max-request-size: 2097152
       consumer:
         fetch-min-bytes: 65536
         fetch-max-wait-ms: 10
         max-poll-records: 200
   ```
   These can also be set through environment variables: `SPRING_KAFKA_PRODUCER_LINGER-MS=5`, etc.

2. **Via `KafkaClientCustomizer`** – the starter exposes a hook (see `OrdersOrchestratorApplication`) that lets you modify the live property map:
   ```java
   @Bean
   KafkaClientCustomizer kafkaTuner() {
     return new KafkaClientCustomizer() {
       @Override public void customizeProducer(Map<String, Object> p) {
         p.put(ProducerConfig.LINGER_MS_CONFIG, 5);
         p.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 2_097_152);
       }
       @Override public void customizeConsumer(Map<String, Object> c) {
         c.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
         c.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 65_536);
       }
     };
   }
   ```
   Use this when you need programmatic logic (e.g., profile-based tuning) or you cannot touch deployment config.

3. **Per-profile overrides** – define named Spring profiles (`application-prod.yml`, `application-dev.yml`) so staging mirrors production traffic before you roll out changes.

## Observability & Safe Rollouts

- Set a distinct `spring.kafka.properties.client.id` per deployment to trace metrics.
- Watch Micrometer counters (`orch.transform.fail`, `orch.db.circuit.open` ) and Kafka lag dashboards while testing new settings.
- Keep `transaction.timeout.ms` above the longest expected DB+transform time but below the consumer `max.poll.interval.ms`.
- Always perform back-to-back deployments with the same transactional ID prefix when changing producer configs, otherwise in-flight transactions may abort.
- When in doubt, revert to the starter defaults by removing overrides and restarting; the auto-config will reapply the safe baseline.
